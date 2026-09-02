/**
 * Amua - An open source modeling framework.
 * Copyright (C) 2017-2024 Zachary J. Ward
 *
 * This file is part of Amua. Amua is free software: you can redistribute
 * it and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Amua is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Amua.  If not, see <http://www.gnu.org/licenses/>.
 */


package main;

import java.awt.EventQueue;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import gui.frmMain;

/**
 * Lets a model that is opened from the desktop join the Amua window that is already running,
 * as another tab, instead of starting a second copy of the application.
 * <br><br>
 * The running instance listens on a port the operating system picks, on the loopback interface
 * only, and notes the port in a small file.  A later launch reads that file and passes its file
 * names over, then exits.  Binding to loopback keeps this invisible to the firewall, and a token
 * shared through the same file means another account on the machine cannot ask Amua to open
 * files.  A stale file left by a crash simply fails to connect, and the new instance takes over.
 * <br><br>
 * Only a launch that carries model files hands over.  Starting Amua from its icon always opens a
 * new window, so two models can still be compared side by side.
 */
public final class SingleInstance{

	/** Long enough for a busy machine to answer, short enough not to delay a genuine cold start */
	private static final int CONNECT_TIMEOUT_MS=500;
	private static final int READ_TIMEOUT_MS=3000;

	private SingleInstance(){} //static only

	/**
	 * Asks an already running Amua to open these models.
	 * @return true if one accepted them, in which case this launch has nothing left to do
	 */
	public static boolean handOff(List<File> models){
		if(models==null || models.isEmpty()){return(false);}
		Socket socket=null;
		try{
			String lines[]=readPortFile();
			if(lines==null){return(false);} //nothing running, or nothing that left a note

			int port=Integer.parseInt(lines[0].trim());
			String token=lines[1].trim();

			socket=new Socket();
			socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(READ_TIMEOUT_MS);

			BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),
					StandardCharsets.UTF_8));
			out.write(token); out.newLine();
			for(int i=0; i<models.size(); i++){
				out.write(models.get(i).getAbsolutePath()); out.newLine();
			}
			out.write("."); out.newLine(); //end of list
			out.flush();

			BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),
					StandardCharsets.UTF_8));
			return("OK".equals(in.readLine()));

		}catch(Throwable e){
			return(false); //no one listening, or something is wrong: just start normally
		}finally{
			if(socket!=null){
				try{socket.close();}catch(Throwable e){}
			}
		}
	}

	/**
	 * Starts listening so that later launches can pass their models to this window.  Quietly does
	 * nothing if another instance already holds the port, in which case this window is simply a
	 * second one the user asked for.
	 */
	public static void listen(final frmMain window){
		try{
			final ServerSocket server=new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
			final String token=UUID.randomUUID().toString();
			writePortFile(server.getLocalPort(), token);

			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){
				public void run(){
					portFile().delete(); //do not leave a note pointing at a port nobody holds
					try{server.close();}catch(Throwable e){}
				}
			}));

			Thread listener=new Thread(new Runnable(){
				public void run(){
					while(!server.isClosed()){
						Socket socket=null;
						try{
							socket=server.accept();
							socket.setSoTimeout(READ_TIMEOUT_MS);
							serve(socket, token, window);
						}catch(Throwable e){
							//a bad or interrupted request must never stop us serving the next one
						}finally{
							if(socket!=null){
								try{socket.close();}catch(Throwable e){}
							}
						}
					}
				}
			});
			listener.setDaemon(true);
			listener.setName("Amua single instance listener");
			listener.start();

		}catch(Throwable e){
			//already taken, or sockets are unavailable: carry on as an ordinary window
		}
	}

	/** Reads one request and opens whatever it names */
	private static void serve(Socket socket, String token, final frmMain window) throws Exception{
		BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),
				StandardCharsets.UTF_8));
		String sent=in.readLine();
		if(sent==null || !sent.equals(token)){return;} //not from a copy of Amua that we told

		final ArrayList<File> models=new ArrayList<File>();
		String line=in.readLine();
		while(line!=null && !line.equals(".")){
			if(!line.trim().isEmpty()){models.add(new File(line));}
			line=in.readLine();
		}

		BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),
				StandardCharsets.UTF_8));
		out.write("OK"); out.newLine();
		out.flush();

		EventQueue.invokeLater(new Runnable(){
			public void run(){
				for(int i=0; i<models.size(); i++){
					window.openModel(models.get(i));
				}
				//Windows does not always let a background process raise a window, so this can end
				//up flashing in the task bar instead.  The tab is added either way.
				if(window.frmMain.getExtendedState()==Frame.ICONIFIED){
					window.frmMain.setExtendedState(Frame.NORMAL);
				}
				window.frmMain.toFront();
				window.frmMain.requestFocus();
			}
		});
	}


	//******************** where the running instance leaves its note ********************

	private static File portFile(){
		String base=System.getenv("LOCALAPPDATA"); //survives temp folder cleaners
		File dir;
		if(base!=null && !base.trim().isEmpty()){dir=new File(base, "Amua");}
		else{dir=new File(System.getProperty("java.io.tmpdir"), "Amua");}
		dir.mkdirs();
		return(new File(dir, "instance"));
	}

	private static void writePortFile(int port, String token) throws Exception{
		String content=port + System.lineSeparator() + token + System.lineSeparator();
		Files.write(portFile().toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	/** @return the port and token, or null if there is no usable note */
	private static String[] readPortFile(){
		try{
			File file=portFile();
			if(!file.exists()){return(null);}
			String content=new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			String lines[]=content.split("\r\n|\n|\r");
			if(lines.length<2 || lines[0].trim().isEmpty() || lines[1].trim().isEmpty()){return(null);}
			return(lines);
		}catch(Throwable e){
			return(null);
		}
	}

}
