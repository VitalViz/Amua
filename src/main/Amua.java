/**
 * Amua - An open source modeling framework.
 * Copyright (C) 2017 Zachary J. Ward
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
//Utility class
package main;

import java.awt.EventQueue;
import java.io.File;
import java.util.ArrayList;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.UIManager.LookAndFeelInfo;

import cluster.ClusterRun;
import gui.frmMain;

public class Amua {
	
	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		//Builds of this fork carry a _vs suffix so they are never mistaken for the upstream
		//release of the same number.  It reaches the jar name, the About box, the error log and
		//the version stamped into saved models, and the update check ignores it when comparing
		//against upstream.  Keep it on every future bump; build.ps1 will not build without it.
		String version="0.3.8_vs";
		
		//Windows hands a double-clicked model to us as a command line argument, and so does Linux
		//through a .desktop entry.  A cluster run always passes four arguments (model, inputs,
		//output path, iteration), so arguments that are all .amua paths can only mean "open these".
		ArrayList<File> modelFiles=getModelFiles(args);
		boolean gui=(args.length==0 || modelFiles!=null);

		//A model opened from the desktop belongs in the window that is already up, as another tab.
		//Checked before anything is built so that nothing flashes on screen on the way out.
		if(modelFiles!=null && SingleInstance.handOff(modelFiles)) {
			return;
		}

		if(gui==true) { //show desktop gui
			//get current OS
			String curOS=System.getProperty("os.name").toLowerCase();
			if(curOS.contains("mac")){ //if Mac
				System.setProperty( "com.apple.mrj.application.apple.menu.about.name", "Amua" );
				System.setProperty( "com.apple.macos.useScreenMenuBar", "true" );
				System.setProperty( "apple.laf.useScreenMenuBar", "true" ); // for older versions of Java
			}

			try {
				for (LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
					if ("Nimbus".equals(info.getName())) {
						UIManager.setLookAndFeel(info.getClassName());
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

			EventQueue.invokeLater(new Runnable() {
				public void run() {
					try {
						frmMain window = new frmMain(version);
						window.frmMain.setVisible(true);
						//before the update check, which reaches the network and would delay the model
						if(modelFiles!=null) {
							for(int i=0; i<modelFiles.size(); i++) {
								window.openModel(modelFiles.get(i)); //reports its own errors to the console
							}
						}
						SingleInstance.listen(window); //later launches hand their models to us
						registerFileAssociation(window);
						window.checkUpdates();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
		}
		else { //process arguments for cluster run
			new ClusterRun(args,version);
		}

	}

	/**
	 * Points Windows at this copy of Amua for .amua files, so a model opens on a double-click.
	 * Runs off the event thread because it shells out to the registry, and does nothing at all
	 * on other platforms or when Amua is started from a plain jar.
	 */
	private static void registerFileAssociation(final frmMain window) {
		Thread thread=new Thread(new Runnable() {
			public void run() {
				WindowsFileAssociation.Status status=WindowsFileAssociation.registerIfNeeded();
				//Windows honours a file type the user picked themselves, and no program is allowed
				//to override it, so the last step has to be theirs.  Said once, never repeated.
				if(status==WindowsFileAssociation.Status.USER_CHOICE_ELSEWHERE
						&& WindowsFileAssociation.shouldWarnOnce()) {
					EventQueue.invokeLater(new Runnable() {
						public void run() {
							JOptionPane.showMessageDialog(window.frmMain,
									window.language.message.getString("info.file_assoc_user_choice"),
									window.language.base.getString("title.file_association"),
									JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			}
		});
		thread.setDaemon(true); //must never hold the application open
		thread.start();
	}

	/**
	 * The model files named on the command line, or null if the arguments are anything else and
	 * should go to a cluster run.  Every argument has to be a .amua path to qualify, so the four
	 * arguments of a cluster run can never be mistaken for a request to open a model.
	 * <br><br>
	 * The file is not required to exist: opening the window and reporting the missing file is far
	 * more use than the silence a user would otherwise get from a stale shortcut.
	 * <br><br>
	 * macOS never comes through here.  It launches the application with no arguments and then
	 * sends an open-document event, which java.awt.Desktop exposes as an open file handler; that
	 * handler would call frmMain.openModel in the same way this does.
	 */
	private static ArrayList<File> getModelFiles(String args[]) {
		if(args.length==0) {return(null);}
		ArrayList<File> files=new ArrayList<File>();
		for(int i=0; i<args.length; i++) {
			if(args[i]==null || !args[i].toLowerCase().endsWith(".amua")) {
				return(null); //not a model file, so these are cluster run arguments
			}
			files.add(new File(args[i]));
		}
		return(files);
	}
}