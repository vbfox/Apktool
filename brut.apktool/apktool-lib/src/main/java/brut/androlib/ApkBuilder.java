/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.apk.ApkInfo;
import brut.androlib.apk.UsesFramework;
import brut.androlib.res.Framework;
import brut.androlib.res.data.ResConfigFlags;
import brut.androlib.res.xml.ResXmlPatcher;
import brut.androlib.src.SmaliBuilder;
import brut.common.BrutException;
import brut.common.InvalidUnknownFileException;
import brut.common.RootUnknownFileException;
import brut.common.TraversalUnknownFileException;
import brut.directory.Directory;
import brut.directory.DirectoryException;
import brut.directory.ExtFile;
import brut.directory.ZipUtils;
import brut.util.BrutIO;
import brut.util.OS;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkBuilder {
    abstract static class BuildAction {

}

    static final class BuildSourcesAction extends BuildAction {
    private final String mFolder;
    private final String mFileName;
    private final boolean mBuildSmali;

    public BuildSourcesAction(String folder, String fileName, boolean buildSmali) {
        mFolder = folder;
        mFileName = fileName;
        mBuildSmali = buildSmali;
    }

    public String getFolder() {
        return mFolder;
    }

    public String getFileName() {
        return mFileName;
    }

    public boolean getBuildSmali() {
        return mBuildSmali;
    }
}

    static final class BuildManifestAndResourcesAction extends BuildAction {
    private final File mManifest;
    private final File mManifestOriginal;

    public BuildManifestAndResourcesAction(File manifest, File manifestOriginal) {

        this.mManifest = manifest;
        this.mManifestOriginal = manifestOriginal;
    }

    public File getManifest() {
        return mManifest;
    }

    public File getManifestOriginal() {
        return mManifestOriginal;
    }
}

    static final class BuildLibraryAction extends BuildAction {
    private final String mFolder;

    public BuildLibraryAction(String folder) {
        mFolder = folder;
    }

    public String getFolder() {
        return mFolder;
    }
}

    static final class CopyOriginalFilesAction extends BuildAction {
    }

    private final static Logger LOGGER = Logger.getLogger(ApkBuilder.class.getName());

    private final Config mConfig;
    private final ExtFile mApkDir;
    private ApkInfo mApkInfo;
    private int mMinSdkVersion = 0;

    private final static String APK_DIRNAME = "build/apk";
    private final static String UNK_DIRNAME = "unknown";
    private final static String[] APK_RESOURCES_FILENAMES = new String[] {
        "resources.arsc", "AndroidManifest.xml", "res", "r", "R" };
    private final static String[] APK_RESOURCES_WITHOUT_RES_FILENAMES = new String[] {
        "resources.arsc", "AndroidManifest.xml" };
    private final static String[] APP_RESOURCES_FILENAMES = new String[] {
        "AndroidManifest.xml", "res" };
    private final static String[] APK_MANIFEST_FILENAMES = new String[] {
        "AndroidManifest.xml" };

    public ApkBuilder(ExtFile apkDir) {
        this(Config.getDefaultConfig(), apkDir);
    }

    public ApkBuilder(Config config, ExtFile apkDir) {
        mConfig = config;
        mApkDir = apkDir;
    }

    public void build(File outFile) throws BrutException {
        LOGGER.info("Using Apktool " + ApktoolProperties.getVersion());

        mApkInfo = ApkInfo.load(mApkDir);

        if (mApkInfo.getSdkInfo() != null && mApkInfo.getSdkInfo().get("minSdkVersion") != null) {
            String minSdkVersion = mApkInfo.getSdkInfo().get("minSdkVersion");
            mMinSdkVersion = mApkInfo.getMinSdkVersionFromAndroidCodename(minSdkVersion);
        }

        if (outFile == null) {
            String outFileName = mApkInfo.apkFileName;
            outFile = new File(mApkDir, "dist" + File.separator + (outFileName == null ? "out.apk" : outFileName));
        }

        //noinspection ResultOfMethodCallIgnored
        new File(mApkDir, APK_DIRNAME).mkdirs();
        File manifest = new File(mApkDir, "AndroidManifest.xml");
        File manifestOriginal = new File(mApkDir, "AndroidManifest.xml.orig");

        // build a list of actions that will be run in parallel
        List<BuildAction> buildActions = new ArrayList<>();
        buildActions.add(new BuildManifestAndResourcesAction(manifest, manifestOriginal));
        buildActions.add(new CopyOriginalFilesAction());
        addBuildSourcesAction(buildActions);
        addBuildNonDefaultSourcesActions(buildActions);
        addBuildLibsActions(buildActions);
        // then run them all
        runBuildActionsInParallel(buildActions);

        buildApk(outFile);

        // we must go after the Apk is built, and copy the files in via Zip
        // this is because Aapt won't add files it doesn't know (ex unknown files)
        buildUnknownFiles(outFile);

        // we copied the AndroidManifest.xml to AndroidManifest.xml.orig so we can edit it
        // lets restore the unedited one, to not change the original
        if (manifest.isFile() && manifest.exists() && manifestOriginal.isFile()) {
            try {
                if (new File(mApkDir, "AndroidManifest.xml").delete()) {
                    FileUtils.moveFile(manifestOriginal, manifest);
                }
            } catch (IOException ex) {
                throw new AndrolibException(ex.getMessage());
            }
        }
        LOGGER.info("Built apk into: " + outFile.getPath());
    }

    private void buildManifestFile(File manifest, File manifestOriginal) throws AndrolibException {
        // If we decoded in "raw", we cannot patch AndroidManifest
        if (new File(mApkDir, "resources.arsc").exists()) {
            return;
        }
        if (manifest.isFile() && manifest.exists()) {
            try {
                if (manifestOriginal.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    manifestOriginal.delete();
                }
                FileUtils.copyFile(manifest, manifestOriginal);
                ResXmlPatcher.fixingPublicAttrsInProviderAttributes(manifest);
            } catch (IOException ex) {
                throw new AndrolibException(ex.getMessage());
            }
        }
    }

    private void runBuildAction(BuildAction buildAction) throws BrutException {
        if (buildAction instanceof BuildSourcesAction) {
            BuildSourcesAction typedAction = (BuildSourcesAction) buildAction;
            if (typedAction.getBuildSmali() && typedAction.getFolder() != null) {
                if (!buildSourcesRaw(typedAction.getFileName()) && !buildSourcesSmali(typedAction.getFolder(), typedAction.getFileName())) {
                    LOGGER.warning("Could not find sources");
                }
            } else {
                if (!buildSourcesRaw(typedAction.getFileName())) {
                    LOGGER.warning("Could not find sources");
                }
            }
        } else if (buildAction instanceof BuildLibraryAction) {
            BuildLibraryAction typedAction = (BuildLibraryAction) buildAction;
            buildLibrary(typedAction.getFolder());
        } else if (buildAction instanceof BuildManifestAndResourcesAction) {
            BuildManifestAndResourcesAction typedAction = (BuildManifestAndResourcesAction) buildAction;
            buildManifestFile(typedAction.getManifest(), typedAction.getManifestOriginal());
            buildResources();
        } else if (buildAction instanceof  CopyOriginalFilesAction) {
            buildCopyOriginalFiles();
        } else {
            throw new RuntimeException("Unknown build action");
        }
    }

    private void runBuildActionsInParallel(List<BuildAction> buildActions) throws BrutException {
        try {
            buildActions.parallelStream().forEach(a -> {
                try {
                    runBuildAction(a);
                } catch (BrutException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e){
          Throwable rootCause = ExceptionUtils.getRootCause(e);
          if (rootCause instanceof BrutException) {
              throw (BrutException) rootCause;
          }

          throw e;
        }
    }

    private void addBuildSourcesAction(List<BuildAction> buildActions) {
        buildActions.add(new BuildSourcesAction("smali", "classes.dex", true));
    }

    private void addBuildNonDefaultSourcesActions(List<BuildAction> buildActions) throws AndrolibException {
        try {
            // loop through any smali_ directories for multi-dex apks
            Map<String, Directory> dirs = mApkDir.getDirectory().getDirs();
            for (Map.Entry<String, Directory> directory : dirs.entrySet()) {
                String name = directory.getKey();
                if (name.startsWith("smali_")) {
                    String filename = name.substring(name.indexOf("_") + 1) + ".dex";

                    buildActions.add(new BuildSourcesAction(name, filename, true));
                }
            }

            // loop through any classes#.dex files for multi-dex apks
            File[] dexFiles = mApkDir.listFiles();
            if (dexFiles != null) {
                for (File dex : dexFiles) {
                    // skip classes.dex because we have handled it in buildSources()
                    if (dex.getName().endsWith(".dex") && !dex.getName().equalsIgnoreCase("classes.dex")) {
                        buildActions.add(new BuildSourcesAction(null, dex.getName(), false));
                    }
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private boolean buildSourcesRaw(String filename) throws AndrolibException {
        File working = new File(mApkDir, filename);
        if (!working.exists()) {
            return false;
        }
        File stored = new File(mApkDir, APK_DIRNAME + "/" + filename);
        if (mConfig.forceBuildAll || isModified(working, stored)) {
            LOGGER.info("Copying " + mApkDir.toString() + " " + filename + " file...");
            try {
                BrutIO.copyAndClose(Files.newInputStream(working.toPath()), Files.newOutputStream(stored.toPath()));
                return true;
            } catch (IOException ex) {
                throw new AndrolibException(ex);
            }
        }
        return true;
    }

    private boolean buildSourcesSmali(String folder, String filename) throws AndrolibException {
        ExtFile smaliDir = new ExtFile(mApkDir, folder);
        if (!smaliDir.exists()) {
            return false;
        }
        File dex = new File(mApkDir, APK_DIRNAME + "/" + filename);
        if (!mConfig.forceBuildAll) {
            LOGGER.info("Checking whether sources has changed...");
        }
        if (mConfig.forceBuildAll || isModified(smaliDir, dex)) {
            LOGGER.info("Smaling " + folder + " folder into " + filename + "...");
            //noinspection ResultOfMethodCallIgnored
            dex.delete();
            SmaliBuilder.build(smaliDir, dex, mConfig.apiLevel > 0 ? mConfig.apiLevel : mMinSdkVersion);
        }
        return true;
    }

    private void buildResources() throws BrutException {
        if (!buildResourcesRaw() && !buildResourcesFull() && !buildManifest()) {
            LOGGER.warning("Could not find resources");
        }
    }

    private boolean buildResourcesRaw() throws AndrolibException {
        try {
            if (!new File(mApkDir, "resources.arsc").exists()) {
                return false;
            }
            File apkDir = new File(mApkDir, APK_DIRNAME);
            if (!mConfig.forceBuildAll) {
                LOGGER.info("Checking whether resources has changed...");
            }
            if (mConfig.forceBuildAll || isModified(newFiles(APK_RESOURCES_FILENAMES, mApkDir),
                    newFiles(APK_RESOURCES_FILENAMES, apkDir))) {
                LOGGER.info("Copying raw resources...");
                mApkDir.getDirectory().copyToDir(apkDir, APK_RESOURCES_FILENAMES);
            }
            return true;
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private boolean buildResourcesFull() throws AndrolibException {
        try {
            if (!new File(mApkDir, "res").exists()) {
                return false;
            }
            if (!mConfig.forceBuildAll) {
                LOGGER.info("Checking whether resources has changed...");
            }
            File apkDir = new File(mApkDir, APK_DIRNAME);
            File resourceFile = new File(apkDir.getParent(), "resources.zip");

            if (mConfig.forceBuildAll || isModified(newFiles(APP_RESOURCES_FILENAMES, mApkDir),
                    newFiles(APK_RESOURCES_FILENAMES, apkDir)) || (mConfig.isAapt2() && !isFile(resourceFile))) {
                LOGGER.info("Building resources...");

                if (mConfig.debugMode) {
                    if (mConfig.isAapt2()) {
                        LOGGER.info("Using aapt2 - setting 'debuggable' attribute to 'true' in AndroidManifest.xml");
                        ResXmlPatcher.setApplicationDebugTagTrue(new File(mApkDir, "AndroidManifest.xml"));
                    } else {
                        ResXmlPatcher.removeApplicationDebugTag(new File(mApkDir, "AndroidManifest.xml"));
                    }
                }

                if (mConfig.netSecConf) {
                    ApkInfo meta = ApkInfo.load(new ExtFile(mApkDir));
                    if (meta.getSdkInfo() != null && meta.getSdkInfo().get("targetSdkVersion") != null) {
                        if (Integer.parseInt(meta.getSdkInfo().get("targetSdkVersion")) < ResConfigFlags.SDK_NOUGAT) {
                            LOGGER.warning("Target SDK version is lower than 24! Network Security Configuration might be ignored!");
                        }
                    }
                    File netSecConfOrig = new File(mApkDir, "res/xml/network_security_config.xml");
                    if (netSecConfOrig.exists()) {
                        LOGGER.info("Replacing existing network_security_config.xml!");
                        //noinspection ResultOfMethodCallIgnored
                        netSecConfOrig.delete();
                    }
                    ResXmlPatcher.modNetworkSecurityConfig(netSecConfOrig);
                    ResXmlPatcher.setNetworkSecurityConfig(new File(mApkDir, "AndroidManifest.xml"));
                    LOGGER.info("Added permissive network security config in manifest");
                }

                File apkFile = File.createTempFile("APKTOOL", null);
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
                //noinspection ResultOfMethodCallIgnored
                resourceFile.delete();

                File ninePatch = new File(mApkDir, "9patch");
                if (!ninePatch.exists()) {
                    ninePatch = null;
                }
                AaptInvoker invoker = new AaptInvoker(mConfig, mApkInfo);
                invoker.invokeAapt(apkFile, new File(mApkDir, "AndroidManifest.xml"),
                                   new File(mApkDir, "res"), ninePatch, null, getIncludeFiles());

                ExtFile tmpExtFile = new ExtFile(apkFile);
                Directory tmpDir = tmpExtFile.getDirectory();

                // Sometimes an application is built with a resources.arsc file with no resources,
                // Apktool assumes it will have a rebuilt arsc file, when it doesn't. So if we
                // encounter a copy error, move to a warning and continue on. (#1730)
                try {
                    tmpDir.copyToDir(apkDir,
                            tmpDir.containsDir("res") ? APK_RESOURCES_FILENAMES
                                    : APK_RESOURCES_WITHOUT_RES_FILENAMES);
                } catch (DirectoryException ex) {
                    LOGGER.warning(ex.getMessage());
                } finally {
                    tmpExtFile.close();
                }

                // delete tmpDir
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
            }
            return true;
        } catch (IOException | BrutException | ParserConfigurationException | TransformerException | SAXException ex) {
            throw new AndrolibException(ex);
        }
    }

    private boolean buildManifestRaw() throws AndrolibException {
        try {
            File apkDir = new File(mApkDir, APK_DIRNAME);
            LOGGER.info("Copying raw AndroidManifest.xml...");
            mApkDir.getDirectory().copyToDir(apkDir, APK_MANIFEST_FILENAMES);
            return true;
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private boolean buildManifest() throws BrutException {
        try {
            if (!new File(mApkDir, "AndroidManifest.xml").exists()) {
                return false;
            }
            if (!mConfig.forceBuildAll) {
                LOGGER.info("Checking whether resources has changed...");
            }

            File apkDir = new File(mApkDir, APK_DIRNAME);

            if (mConfig.forceBuildAll || isModified(newFiles(APK_MANIFEST_FILENAMES, mApkDir),
                    newFiles(APK_MANIFEST_FILENAMES, apkDir))) {
                LOGGER.info("Building AndroidManifest.xml...");

                File apkFile = File.createTempFile("APKTOOL", null);
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();

                File ninePatch = new File(mApkDir, "9patch");
                if (!ninePatch.exists()) {
                    ninePatch = null;
                }

                AaptInvoker invoker = new AaptInvoker(mConfig, mApkInfo);
                invoker.invokeAapt(apkFile, new File(mApkDir, "AndroidManifest.xml"),
                                   null, ninePatch, null, getIncludeFiles());

                Directory tmpDir = new ExtFile(apkFile).getDirectory();
                tmpDir.copyToDir(apkDir, APK_MANIFEST_FILENAMES);

                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
            }
            return true;
        } catch (IOException | DirectoryException ex) {
            throw new AndrolibException(ex);
        } catch (AndrolibException ex) {
            LOGGER.warning("Parse AndroidManifest.xml failed, treat it as raw file.");
            return buildManifestRaw();
        }
    }

    private void addBuildLibsActions(List<BuildAction> buildActions) {
        buildActions.add(new BuildLibraryAction("lib"));
        buildActions.add(new BuildLibraryAction("libs"));
        buildActions.add(new BuildLibraryAction("kotlin"));
        buildActions.add(new BuildLibraryAction("META-INF/services"));
    }

    private void buildLibrary(String folder) throws AndrolibException {
        File working = new File(mApkDir, folder);

        if (!working.exists()) {
            return;
        }

        File stored = new File(mApkDir, APK_DIRNAME + "/" + folder);
        if (mConfig.forceBuildAll || isModified(working, stored)) {
            LOGGER.info("Copying libs... (/" + folder + ")");
            try {
                OS.rmdir(stored);
                OS.cpdir(working, stored);
            } catch (BrutException ex) {
                throw new AndrolibException(ex);
            }
        }
    }

    private void buildCopyOriginalFiles() throws AndrolibException {
        if (mConfig.copyOriginalFiles) {
            File originalDir = new File(mApkDir, "original");
            if (originalDir.exists()) {
                try {
                    LOGGER.info("Copy original files...");
                    Directory in = (new ExtFile(originalDir)).getDirectory();
                    if (in.containsFile("AndroidManifest.xml")) {
                        LOGGER.info("Copy AndroidManifest.xml...");
                        in.copyToDir(new File(mApkDir, APK_DIRNAME), "AndroidManifest.xml");
                    }
                    if (in.containsFile("stamp-cert-sha256")) {
                        LOGGER.info("Copy stamp-cert-sha256...");
                        in.copyToDir(new File(mApkDir, APK_DIRNAME), "stamp-cert-sha256");
                    }
                    if (in.containsDir("META-INF")) {
                        LOGGER.info("Copy META-INF...");
                        in.copyToDir(new File(mApkDir, APK_DIRNAME), "META-INF");
                    }
                } catch (DirectoryException ex) {
                    throw new AndrolibException(ex);
                }
            }
        }
    }

    private void buildUnknownFiles(File outFile) throws AndrolibException {
        if (mApkInfo.unknownFiles != null) {
            LOGGER.info("Copying unknown files/dir...");

            Map<String, String> files = mApkInfo.unknownFiles;
            File tempFile = new File(outFile.getParent(), outFile.getName() + ".apktool_temp");
            boolean renamed = outFile.renameTo(tempFile);
            if (!renamed) {
                throw new AndrolibException("Unable to rename temporary file");
            }

            try (
                ZipFile inputFile = new ZipFile(tempFile);
                ZipOutputStream actualOutput = new ZipOutputStream(Files.newOutputStream(outFile.toPath()))
            ) {
                actualOutput.setLevel(mConfig.zipCompressionLevel);
                copyExistingFiles(inputFile, actualOutput);
                copyUnknownFiles(actualOutput, files);
            } catch (IOException | BrutException ex) {
                throw new AndrolibException(ex);
            }

            // Remove our temporary file.
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    private void copyExistingFiles(ZipFile inputFile, ZipOutputStream outputFile) throws IOException {
        // First, copy the contents from the existing outFile:
        Enumeration<? extends ZipEntry> entries = inputFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = new ZipEntry(entries.nextElement());

            // We can't reuse the compressed size because it depends on compression sizes.
            entry.setCompressedSize(-1);
            outputFile.putNextEntry(entry);

            // No need to create directory entries in the final apk
            if (!entry.isDirectory()) {
                BrutIO.copy(inputFile, outputFile, entry);
            }

            outputFile.closeEntry();
        }
    }

    private void copyUnknownFiles(ZipOutputStream outputFile, Map<String, String> files)
            throws BrutException, IOException {
        File unknownFileDir = new File(mApkDir, UNK_DIRNAME);

        // loop through unknown files
        for (Map.Entry<String,String> unknownFileInfo : files.entrySet()) {
            File inputFile;

            try {
                inputFile = new File(unknownFileDir, BrutIO.sanitizeUnknownFile(unknownFileDir, unknownFileInfo.getKey()));
            } catch (RootUnknownFileException | InvalidUnknownFileException | TraversalUnknownFileException exception) {
                LOGGER.warning(String.format("Skipping file %s (%s)", unknownFileInfo.getKey(), exception.getMessage()));
                continue;
            }

            if (inputFile.isDirectory()) {
                continue;
            }

            ZipEntry newEntry = new ZipEntry(unknownFileInfo.getKey());
            int method = Integer.parseInt(unknownFileInfo.getValue());
            LOGGER.fine(String.format("Copying unknown file %s with method %d", unknownFileInfo.getKey(), method));
            if (method == ZipEntry.STORED) {
                newEntry.setMethod(ZipEntry.STORED);
                newEntry.setSize(inputFile.length());
                newEntry.setCompressedSize(-1);
                BufferedInputStream unknownFile = new BufferedInputStream(Files.newInputStream(inputFile.toPath()));
                CRC32 crc = BrutIO.calculateCrc(unknownFile);
                newEntry.setCrc(crc.getValue());
                unknownFile.close();
            } else {
                newEntry.setMethod(ZipEntry.DEFLATED);
            }
            outputFile.putNextEntry(newEntry);

            BrutIO.copy(inputFile, outputFile);
            outputFile.closeEntry();
        }
    }

    private void buildApk(File outApk) throws AndrolibException {
        LOGGER.info("Building apk file...");
        if (outApk.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outApk.delete();
        } else {
            File outDir = outApk.getParentFile();
            if (outDir != null && !outDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outDir.mkdirs();
            }
        }
        File assetDir = new File(mApkDir, "assets");
        if (!assetDir.exists()) {
            assetDir = null;
        }
        zipPackage(outApk, new File(mApkDir, APK_DIRNAME), assetDir);
    }

    private void zipPackage(File apkFile, File rawDir, File assetDir) throws AndrolibException {
        try {
            ZipUtils.zipFolders(rawDir, apkFile, assetDir, mApkInfo.doNotCompress, mConfig.zipCompressionLevel);
        } catch (IOException | BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    private File[] getIncludeFiles() throws AndrolibException {
        UsesFramework usesFramework = mApkInfo.usesFramework;
        if (usesFramework == null) {
            return null;
        }

        List<Integer> ids = usesFramework.ids;
        if (ids == null || ids.isEmpty()) {
            return null;
        }

        Framework framework = new Framework(mConfig);
        String tag = usesFramework.tag;
        File[] files = new File[ids.size()];
        int i = 0;
        for (int id : ids) {
            files[i++] = framework.getFrameworkApk(id, tag);
        }
        return files;
    }

    private boolean isModified(File working, File stored) {
        return !stored.exists() || BrutIO.recursiveModifiedTime(working) > BrutIO .recursiveModifiedTime(stored);
    }

    private boolean isFile(File working) {
        return working.exists();
    }

    private boolean isModified(File[] working, File[] stored) {
        for (File file : stored) {
            if (!file.exists()) {
                return true;
            }
        }
        return BrutIO.recursiveModifiedTime(working) > BrutIO.recursiveModifiedTime(stored);
    }

    private File[] newFiles(String[] names, File dir) {
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new File(dir, names[i]);
        }
        return files;
    }

    public boolean detectWhetherAppIsFramework() throws AndrolibException {
        File publicXml = new File(mApkDir, "res/values/public.xml");
        if (!publicXml.exists()) {
            return false;
        }

        Iterator<String> it;
        try {
            it = IOUtils.lineIterator(new FileReader(new File(mApkDir, "res/values/public.xml")));
        } catch (FileNotFoundException ex) {
            throw new AndrolibException(
                "Could not detect whether app is framework one", ex);
        }
        it.next();
        it.next();
        return it.next().contains("0x01");
    }
}
