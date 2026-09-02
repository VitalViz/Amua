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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.prefs.Preferences;

/**
 * Teaches Windows that .amua files belong to this copy of Amua, so a model opens on a
 * double-click.
 * <br><br>
 * Everything is written under HKEY_CURRENT_USER, which needs no administrator rights, and only
 * from the packaged Windows build: a plain jar has no launcher to register and is left alone.
 * The registered command is compared with this installation's own location on every start, so
 * moving or renaming the folder repairs the association rather than breaking it.
 * <br><br>
 * Windows lets a user pin an extension to a program of their choosing, recorded under
 * FileExts\.amua\UserChoice, and that choice overrides anything written here.  It is protected
 * by a hash precisely so that software cannot hijack file types, so it cannot be set from here.
 * When it points elsewhere, {@link #registerIfNeeded()} reports {@link Status#USER_CHOICE_ELSEWHERE}
 * and the caller can explain the one manual step needed.
 */
public final class WindowsFileAssociation{

	private static final String EXT=".amua";
	private static final String PROG_ID="Amua.Model";
	private static final String CLASSES="HKCU" + sep() + "Software" + sep() + "Classes";
	private static final String USER_CHOICE="HKCU" + sep() + "Software" + sep() + "Microsoft" + sep()
			+ "Windows" + sep() + "CurrentVersion" + sep() + "Explorer" + sep() + "FileExts" + sep()
			+ EXT + sep() + "UserChoice";
	/** Set by unregister-amua.bat so that a user who removes the association keeps it removed */
	private static final String OPT_OUT_KEY="HKCU" + sep() + "Software" + sep() + "Amua";
	private static final String OPT_OUT_VALUE="NoFileAssociation";

	private WindowsFileAssociation(){} //static only

	public enum Status{
		/** not Windows, so there is nothing to do */
		NOT_WINDOWS,
		/** running from a plain jar rather than the packaged build */
		NOT_PACKAGED,
		/** the user asked for no association, via unregister-amua.bat */
		OPTED_OUT,
		/** already pointing at this installation */
		ALREADY_REGISTERED,
		/** registered for the first time */
		REGISTERED,
		/** the installation had moved and the association was repaired */
		REPAIRED,
		/** registered, but Windows opens .amua with something else because the user chose it */
		USER_CHOICE_ELSEWHERE,
		/** something went wrong; the association is simply not set up */
		FAILED
	}

	/** a backslash, built rather than written, so no source escape can mangle it */
	private static String sep(){
		return(String.valueOf((char)92));
	}

	private static String quote(){
		return(String.valueOf((char)34));
	}

	/**
	 * Points .amua at this installation unless it already is, doing nothing outside the packaged
	 * Windows build.  Safe to call on every start: the common case is a single registry read.
	 */
	public static Status registerIfNeeded(){
		try{
			if(!System.getProperty("os.name","").toLowerCase().contains("win")){
				return(Status.NOT_WINDOWS);
			}
			String appPath=System.getProperty("jpackage.app-path"); //the launcher, set by jpackage
			if(appPath==null || appPath.trim().isEmpty()){
				return(Status.NOT_PACKAGED);
			}
			if(readRegistry(OPT_OUT_KEY, OPT_OUT_VALUE)!=null){
				return(Status.OPTED_OUT);
			}

			String wanted=quote() + appPath + quote() + " " + quote() + "%1" + quote();
			String current=readRegistry(CLASSES + sep() + PROG_ID + sep() + "shell" + sep() + "open"
					+ sep() + "command", null);
			boolean firstTime=(current==null);

			if(!wanted.equalsIgnoreCase(current==null?"":current.trim())){
				if(!writeAssociation(appPath)){
					return(Status.FAILED);
				}
				refreshIcons();
			}
			else if(!blockedByUserChoice()){
				return(Status.ALREADY_REGISTERED);
			}

			if(blockedByUserChoice()){
				return(Status.USER_CHOICE_ELSEWHERE);
			}
			return(firstTime?Status.REGISTERED:Status.REPAIRED);

		}catch(Throwable e){ //never let this stop the application from starting
			return(Status.FAILED);
		}
	}

	/** True if Windows has been told to open .amua with a program other than Amua */
	private static boolean blockedByUserChoice(){
		String chosen=readRegistry(USER_CHOICE, "ProgId");
		return(chosen!=null && !chosen.trim().equalsIgnoreCase(PROG_ID));
	}

	/**
	 * Writes the keys through a .reg file rather than a series of "reg add" calls: the file
	 * format has one well defined way to escape a path, whereas passing quoted values through a
	 * command line is a well known source of mangling.
	 */
	private static boolean writeAssociation(String appPath) throws IOException, InterruptedException{
		File file=File.createTempFile("amua-assoc", ".reg");
		try{
			//UTF-16LE with a byte order mark: what reg import expects, and the only encoding that
			//survives a user name with an accent in it
			byte bom[]=new byte[]{(byte)0xFF,(byte)0xFE};
			byte body[]=buildRegFile(appPath).getBytes(StandardCharsets.UTF_16LE);
			byte all[]=new byte[bom.length+body.length];
			System.arraycopy(bom,0,all,0,bom.length);
			System.arraycopy(body,0,all,bom.length,body.length);
			Files.write(file.toPath(), all);

			Process p=new ProcessBuilder("reg","import",file.getAbsolutePath())
					.redirectErrorStream(true).start();
			drain(p);
			return(p.waitFor()==0);
		}finally{
			file.delete();
		}
	}

	/** The registry script, kept separate from writing it so that it can be inspected and tested */
	static String buildRegFile(String appPath){
		String command=regValue(quote() + appPath + quote() + " " + quote() + "%1" + quote());
		String icon=regValue(quote() + appPath + quote() + ",0"); //quoted: the path may hold spaces
		String launcher=new File(appPath).getName(); //Amua.exe

		StringBuilder reg=new StringBuilder();
		reg.append("Windows Registry Editor Version 5.00").append("\r\n\r\n");
		key(reg, CLASSES + sep() + EXT);
		reg.append("@=").append(regValue(PROG_ID)).append("\r\n\r\n");
		key(reg, CLASSES + sep() + EXT + sep() + "OpenWithProgids");
		reg.append(regValue(PROG_ID)).append("=hex(0):").append("\r\n\r\n");
		key(reg, CLASSES + sep() + PROG_ID);
		reg.append("@=").append(regValue("Amua Model")).append("\r\n\r\n");
		key(reg, CLASSES + sep() + PROG_ID + sep() + "DefaultIcon");
		reg.append("@=").append(icon).append("\r\n\r\n");
		key(reg, CLASSES + sep() + PROG_ID + sep() + "shell" + sep() + "open" + sep() + "command");
		reg.append("@=").append(command).append("\r\n\r\n");
		key(reg, CLASSES + sep() + "Applications" + sep() + launcher);
		reg.append("@=").append(regValue("Amua")).append("\r\n\r\n");
		key(reg, CLASSES + sep() + "Applications" + sep() + launcher + sep() + "shell" + sep()
				+ "open" + sep() + "command");
		reg.append("@=").append(command).append("\r\n\r\n");
		key(reg, CLASSES + sep() + "Applications" + sep() + launcher + sep() + "SupportedTypes");
		reg.append(regValue(EXT)).append("=").append(regValue("")).append("\r\n\r\n");
		return(reg.toString());
	}

	private static void key(StringBuilder reg, String hkcuPath){
		//the .reg format spells the hive out in full
		reg.append("[").append(hkcuPath.replaceFirst("HKCU","HKEY_CURRENT_USER")).append("]\r\n");
	}

	/** A value as a .reg file writes it: wrapped in quotes, with backslashes and quotes escaped */
	private static String regValue(String data){
		return(quote() + regEscape(data) + quote());
	}

	private static String regEscape(String data){
		return(data.replace(sep(), sep()+sep()).replace(quote(), sep()+quote()));
	}

	/**
	 * Reads one registry value, or null when the key or value does not exist.
	 * <br><br>
	 * Goes through "reg export" rather than "reg query" on purpose.  Query prints to the console
	 * in the OEM code page, so a path holding any character outside plain ASCII comes back
	 * corrupted, the comparison against our own location never matches, and Amua rewrites the
	 * association on every start.  Export writes a UTF-16 file in the same format we import, so
	 * the value survives intact.
	 * @param valueName null for the key's default value
	 */
	private static String readRegistry(String key, String valueName){
		File file=null;
		try{
			file=File.createTempFile("amua-read", ".reg");
			file.delete(); //reg export will not overwrite silently unless the file is absent
			Process p=new ProcessBuilder("reg","export",key,file.getAbsolutePath(),"/y")
					.redirectErrorStream(true).start();
			drain(p);
			if(p.waitFor()!=0 || !file.exists()){return(null);}

			String content=new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_16LE);
			String prefix=(valueName==null)?"@=":(regValue(valueName)+"=");
			String lines[]=content.split("\r\n|\n");
			for(int i=0; i<lines.length; i++){
				String line=lines[i].trim();
				if(line.startsWith(prefix)){
					String raw=line.substring(prefix.length());
					if(raw.startsWith(quote()) && raw.endsWith(quote()) && raw.length()>=2){
						return(regUnescape(raw.substring(1, raw.length()-1)));
					}
					return(raw); //dword: or hex:, where only presence matters to us
				}
			}
			return(null);
		}catch(Throwable e){
			return(null);
		}finally{
			if(file!=null){file.delete();}
		}
	}

	/** Undoes {@link #regEscape} */
	private static String regUnescape(String data){
		return(data.replace(sep()+quote(), quote()).replace(sep()+sep(), sep()));
	}

	private static String drain(Process p) throws IOException{
		StringBuilder out=new StringBuilder();
		BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream()));
		try{
			String line=reader.readLine();
			while(line!=null){
				out.append(line).append("\n");
				line=reader.readLine();
			}
		}finally{
			reader.close();
		}
		return(out.toString());
	}

	/** Asks Explorer to pick up the new icon now rather than after a sign-out.  Best effort. */
	private static void refreshIcons(){
		try{
			new ProcessBuilder("ie4uinit.exe","-show").redirectErrorStream(true).start();
		}catch(Throwable e){
			//an out of date icon is not worth reporting
		}
	}


	//******************** one-time notice ********************

	private static final String WARNED="warnedFileAssociationUserChoice";

	/** True the first time only, so the user is told once and never nagged again */
	public static boolean shouldWarnOnce(){
		try{
			Preferences prefs=Preferences.userNodeForPackage(WindowsFileAssociation.class);
			if(prefs.getBoolean(WARNED,false)){return(false);}
			prefs.putBoolean(WARNED,true);
			prefs.flush();
			return(true);
		}catch(Throwable e){
			return(false); //if the preference cannot be stored, stay quiet rather than nag forever
		}
	}

}
