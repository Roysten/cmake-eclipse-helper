package nl.usetechnology.cmake;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.usetechnology.cmake.helper.FileContentIO;
import nl.usetechnology.cmake.helper.PluginDataIO;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;


public class CMakeLauncher {
	// FIXME: Protect, that jobs are not scheduled in parallel for the same project
	
	class StreamGobbler extends Thread {
		private StringBuilder sb = new StringBuilder();
		private InputStream in;
		public StreamGobbler(InputStream in) {
			this.in = in;
		}
		
		@Override
		public void run() {
			try {
				BufferedReader br = new BufferedReader(new InputStreamReader(in));
				String line;
				while((line = br.readLine()) != null) {
					sb.append(line).append('\n');
				}
				br.close();
			} catch(IOException ioe) {
				
			}
		}
		
		public CharSequence getOutput() {
			return sb;
		}
		
	}

	private static final String GENERATE_ECLIPSE_PROJECT = "-G \"$BUILD_SYS$\" -D_ECLIPSE_VERSION=$VERSION$ -DCMAKE_ECLIPSE_GENERATE_LINKED_RESOURCES=FALSE";
	
	private static final String SETUP_MODULE_PATH = "-DCMAKE_MODULE_PATH=\"$PATH_TO_MODULES$\"";

	private static final String ARCH_BIN_DIR = PluginDataIO.BIN_DIR + File.separator + "$ARCH$";
	
	private static final String SETUP_BIN_DIR = "-H. -B" + ARCH_BIN_DIR + " -DCMAKE_TOOLCHAIN_FILE=\"$PATH_TO_TOOLCHAIN_FILE$\" -DCMAKE_ECLIPSE_MAKE_ARGUMENTS=\"-C " + ARCH_BIN_DIR + " $MAKE_ARGS$\"";

	private static final String SETUP_BIN_DIR_NO_TOOLCHAIN = "-H. -B"+ARCH_BIN_DIR+" -DCMAKE_ECLIPSE_MAKE_ARGUMENTS=\"-C " + ARCH_BIN_DIR + " $MAKE_ARGS$\"";

	private static final String CMAKE_BUILD_TYPE = "-DCMAKE_BUILD_TYPE=$BUILDTYPE$";

	private static final Color black = new Color(Display.getCurrent(), 0, 0, 0);
	private static final Color red = new Color(Display.getCurrent(), 255, 0, 0);

	private static final CMakeLauncher launcher = new CMakeLauncher();
	
	public static CMakeLauncher instance() {
		return launcher;
	}
	
	
	private class CommandBuilder {
		private StringBuilder sb = new StringBuilder("cmake");
		
		CommandBuilder append(String string) {
			sb.append(' ').append(string);
			return this;
		}
		
		boolean execute(IProject project) throws IOException {
			File projectLocation = project.getLocation().makeAbsolute().toFile();
			
			Runtime runtime = Runtime.getRuntime();
			
			String cmdLine = sb.toString();
			Process process = null;
			if (Platform.getOS().equals(Platform.OS_WIN32)) {
				process = runtime.exec(new String[]{"cmd", "/C", cmdLine}, null, projectLocation);
			} else {
				System.out.println(cmdLine);
				process = runtime.exec(new String[]{"sh", "-c", cmdLine}, null, projectLocation);
			}
			
			int exitVal = -1;
			StreamGobbler errordataReader = new StreamGobbler(process.getErrorStream());
			StreamGobbler outputdataReader = new StreamGobbler(process.getInputStream());
			errordataReader.start();
			outputdataReader.start();
			try {
				exitVal = process.waitFor();
				errordataReader.join();
				outputdataReader.join();
			} catch (InterruptedException e) {
				Activator.logError("Error executing cmake", e);
			}
			
			MessageConsole myConsole = Activator.findConsole("CMake Output");
			myConsole.clearConsole();
			MessageConsoleStream out = myConsole.newMessageStream();
			MessageConsoleStream err = myConsole.newMessageStream();
			
			out.setColor(black);
			out.println(cmdLine);
			out.println(outputdataReader.getOutput().toString());
			
			String errorOut = errordataReader.getOutput().toString();
			errorOut = filterErrorOutput(errorOut);
			if(!errorOut.isEmpty()) {
				err.setColor(red);
				err.println(errorOut);
			}
			Activator.showConsole("CMake Output");
			
			return exitVal == 0;
		}

	}
	
	public void setupProject(final IProject project) {
		WorkspaceJob job = new WorkspaceJob("Setup CMakeProject " + project) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor)
					throws CoreException {
				try {
					doSetupProject(project, monitor);
					return Status.OK_STATUS;
				} catch (IOException e) {
					e.printStackTrace();
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to setup Project " + e.getLocalizedMessage());
				}
			}
		};
		job.schedule();
	}

	Pattern eclipseWarning = Pattern.compile("(.*)The build directory is a subdirectory.*which is a sibling of the source directory\\.(.*)", Pattern.DOTALL | Pattern.MULTILINE);
	
	public String filterErrorOutput(String errorOut) {
		Matcher m = eclipseWarning.matcher(errorOut);
		if (m.matches()) {
			errorOut = m.replaceAll("$1$2");
		} else {
			System.err.println("does not match!");
		}
		errorOut = errorOut.trim();
		// No content for warnings anymore? Leave empty!
		if (errorOut.equals("CMake Warning in CMakeLists.txt:")) {
			return "";
		}
		return errorOut;
	}


	private void doSetupProject(IProject project, IProgressMonitor monitor) throws CoreException, IOException {
		CommandBuilder builder = new CommandBuilder();
		appendEclipseProjectSetup(builder);
		appendArchitectureVariables(builder, ProjectSettingsAccessor.retrieveToolchain(project));
		appendBuildTypeVariables(builder, ProjectSettingsAccessor.retrieveBuildType(project));
		builder.append(Activator.getCmakeArgs());
		builder.execute(project);
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		// now relink symbolic links
		copyProjectFiles(project);
	}
	
	public void changeArchitecture(final IProject project, final String architecture) throws CoreException {
		WorkspaceJob job = new WorkspaceJob("Change Architecture of CMakeProject " + project) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor)
					throws CoreException {
				try {
					doChangeArchitecture(project, architecture, monitor);
					return Status.OK_STATUS;
				} catch (IOException e) {
					e.printStackTrace();
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to change architecture of Project " + e.getLocalizedMessage());
				}
			}
		};
		job.schedule();
	}
	
	private void doChangeArchitecture(IProject project, String architecture, IProgressMonitor monitor) throws CoreException, IOException {
		CommandBuilder builder = new CommandBuilder();
		appendEclipseProjectSetup(builder);
		appendArchitectureVariables(builder, architecture);
		appendBuildTypeVariables(builder, ProjectSettingsAccessor.retrieveBuildType(project));
		builder.execute(project);
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		copyProjectFiles(project, architecture);
		ProjectSettingsAccessor.removeAbsoluteProjectPath(project);
	}
	
	public void changeBuildType(final IProject project, final String buildType) throws CoreException {
		WorkspaceJob job = new WorkspaceJob("Change Build-Type of CMakeProject " + project) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor)
					throws CoreException {
				try {
					doChangeBuildType(project, buildType, monitor);
					return Status.OK_STATUS;
				} catch (IOException e) {
					e.printStackTrace();
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Unable to change build type of Project " + e.getLocalizedMessage());
				}
			}
		};
		job.schedule();
	}
	
	
	private void doChangeBuildType(IProject project, String buildType, IProgressMonitor monitor) throws CoreException, IOException {
		CommandBuilder builder = new CommandBuilder();
		appendEclipseProjectSetup(builder);
		appendArchitectureVariables(builder, ProjectSettingsAccessor.retrieveToolchain(project));
		appendBuildTypeVariables(builder, buildType);
		builder.execute(project);
	}
	
	public void scheduleCopyProjectFiles(final IProject project){
		WorkspaceJob job = new WorkspaceJob("Copy generated CMake Eclipse-Project files in " + project) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				copyProjectFiles(project);
				return Status.OK_STATUS;
			}
		};
		job.schedule();

	}
	
	
	private void appendEclipseProjectSetup(CommandBuilder builder) {
		String version = retrieveEclipseVersionString();
		String modulePath = getModulePath();
		
		String parameter = batchReplace(GENERATE_ECLIPSE_PROJECT, new String[]{"BUILD_SYS", "VERSION"},
				new String[]{Activator.getBuildSystemString(), version});

		builder.append(parameter);
		
		if (modulePath != null && !modulePath.isEmpty()) {
			parameter = batchReplace(SETUP_MODULE_PATH, new String[] {"PATH_TO_MODULES"}, new String[] {modulePath});
			builder.append(parameter);
		}
	}
	
	private void appendArchitectureVariables(CommandBuilder builder, String architecture) {
		if(PluginDataIO.getToolchainArchitectures().size() == 0) {
			String parameter = batchReplace(SETUP_BIN_DIR_NO_TOOLCHAIN, new String[]{"ARCH", "MAKE_ARGS"}
			, new String[]{architecture, Activator.getMakeArgs()});
			builder.append(parameter);
		} else {
			if(!isToolchainForArchitectureAvailable(architecture)) {
				System.err.println("FIXME: toolchain for architecture NOT available! (" + architecture +")");
				return; // FIXME: throw CoreException?
			}
			String parameter = batchReplace(SETUP_BIN_DIR, new String[]{"ARCH", "PATH_TO_TOOLCHAIN_FILE", "MAKE_ARGS"},
					new String[]{architecture, getToolchainFilePath(architecture), Activator.getMakeArgs()});
			builder.append(parameter);
		}
	}
	
	private void appendBuildTypeVariables(CommandBuilder builder, String buildType) {
		String parameter = batchReplace(CMAKE_BUILD_TYPE, new String[]{"BUILDTYPE"}, new String[]{buildType});
		builder.append(parameter);
	}

	private void copyProjectFiles(IProject project) {
		copyProjectFiles(project, ProjectSettingsAccessor.retrieveToolchain(project));
	}

	
	private String getModulePath() {
		return PluginDataIO.getPathToModules().toString();
	}

	private String retrieveEclipseVersionString() {
		String product = System.getProperty("eclipse.product");
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		IExtensionPoint point = registry.getExtensionPoint("org.eclipse.core.runtime.products");
		if (point != null) {
			IExtension[] extensions = point.getExtensions();
			for (IExtension ext : extensions) {
				if (product.equals(ext.getUniqueIdentifier())) {
					IContributor contributor = ext.getContributor();
					if (contributor != null) {
						Bundle bundle = Platform.getBundle(contributor.getName());
						if (bundle != null) {
							Version version = bundle.getVersion();
							return version.getMajor() + "." + version.getMinor();
						}
					}
				}
			}
		}
		return null;
	}

	private void copyProjectFiles(IProject project, String architecture) {
		IPath binDir = new Path(batchReplace(ARCH_BIN_DIR, new String[]{"ARCH"}, new String[]{architecture}));
		String fileNamesToCopy[] = new String[] {
				".project",
				".cproject"
		};
		IFolder binDirFolder = project.getFolder(binDir);
		
		for (String fileName : fileNamesToCopy) {
			IFile sourceFile = binDirFolder.getFile(fileName);
			IFile destinationFile = project.getFile(fileName);
			if(!sourceFile.exists()) {
				Activator.logError("Unable to copy " + sourceFile + " does not exist!");
				continue;
			}
			
			if(destinationFile.exists()) {
				copyFileContent(sourceFile, destinationFile);
			} else {
				copyFile(sourceFile, destinationFile);
			}
		}
		ProjectSettingsAccessor.removeAbsoluteProjectPath(project);
		// CMakeNature may have to be registered again after copy
		CMakeNature.scheduleIntegrityCheck(project);
	}
	
	private void copyFile(IFile source, IFile destination) {
		try {
			source.copy(destination.getFullPath(), true, null);
			destination.refreshLocal(IResource.DEPTH_ZERO, null);
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	private void copyFileContent(IFile source, IFile destination) {
		try {
			CharSequence content = FileContentIO.readFileContent(source);
			FileContentIO.writeFileContent(destination, content, null);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private String batchReplace(String string, String[] expressions, String[] replacements) {
		for(int i=0;i<expressions.length;i++) {
			string = string.replace("$" + expressions[i] + "$", replacements[i]);
		}
		return string;
	}

	private boolean isToolchainForArchitectureAvailable(String architecture) {
		File architectureToolchainFilePath = new File(getToolchainFilePath(architecture));
		return architectureToolchainFilePath.exists();
	}

	private String getToolchainFilePath(String architecture) {
		return PluginDataIO.getToolchainPathForArchitecture(architecture).toString();
	}
	
}
