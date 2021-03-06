/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.lib.patch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import com.tencent.tinker.commons.dexpatcher.DexPatchApplier;
import com.tencent.tinker.lib.tinker.Tinker;
import com.tencent.tinker.lib.util.TinkerLog;
import com.tencent.tinker.loader.TinkerParallelDexOptimizer;
import com.tencent.tinker.loader.TinkerRuntimeException;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareDexDiffPatchInfo;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareSecurityCheck;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import dalvik.system.DexFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by zhangshaowen on 16/4/12.
 */
public class DexDiffPatchInternal extends BasePatchInternal {
    protected static final String TAG = "Tinker.DexDiffPatchInternal";

    protected static boolean tryRecoverDexFiles(Tinker manager, ShareSecurityCheck checker, Context context,
                                                String patchVersionDirectory, File patchFile) {
        if (!manager.isEnabledForDex()) {
            TinkerLog.w(TAG, "patch recover, dex is not enabled");
            return true;
        }
        String dexMeta = checker.getMetaContentMap().get(DEX_META_FILE);

        if (dexMeta == null) {
            TinkerLog.w(TAG, "patch recover, dex is not contained");
            return true;
        }

        long begin = SystemClock.elapsedRealtime();
        boolean result = patchDexExtractViaDexDiff(context, patchVersionDirectory, dexMeta, patchFile);
        long cost = SystemClock.elapsedRealtime() - begin;
        TinkerLog.i(TAG, "recover dex result:%b, cost:%d", result, cost);
        return result;
    }

    private static boolean patchDexExtractViaDexDiff(Context context, String patchVersionDirectory, String meta, final File patchFile) {
        String dir = patchVersionDirectory + "/" + DEX_PATH + "/";

        if (!extractDexDiffInternals(context, dir, meta, patchFile, TYPE_DEX)) {
            TinkerLog.w(TAG, "patch recover, extractDiffInternals fail");
            return false;
        }

        final Tinker manager = Tinker.with(context);

        File dexFiles = new File(dir);
        File[] files = dexFiles.listFiles();

        if (files != null) {
            final String optimizeDexDirectory = patchVersionDirectory + "/" + DEX_OPTIMIZE_PATH + "/";
            File optimizeDexDirectoryFile = new File(optimizeDexDirectory);

            if (!optimizeDexDirectoryFile.exists() && !optimizeDexDirectoryFile.mkdirs()) {
                TinkerLog.w(TAG, "patch recover, make optimizeDexDirectoryFile fail");
                return false;
            }

            TinkerLog.w(TAG, "patch recover, try to optimize dex file count:%d", files.length);

            boolean isSuccess = TinkerParallelDexOptimizer.optimizeAll(
                    files, optimizeDexDirectoryFile,
                    new TinkerParallelDexOptimizer.ResultCallback() {
                        long startTime;
                        @Override
                        public void onStart(File dexFile, File optimizedDir) {
                            startTime = System.currentTimeMillis();
                            TinkerLog.i(TAG, "start to optimize dex %s", dexFile.getPath());
                        }

                        @Override
                        public void onSuccess(File dexFile, File optimizedDir) {
                            // Do nothing.
                            TinkerLog.i(TAG, "success to optimize dex %s use time %d",
                                dexFile.getPath(), (System.currentTimeMillis() - startTime));
                        }

                        @Override
                        public void onFailed(File dexFile, File optimizedDir, Throwable thr) {
                            TinkerLog.i(TAG, "fail to optimize dex %s use time %d",
                                dexFile.getPath(), (System.currentTimeMillis() - startTime));
                            SharePatchFileUtil.safeDeleteFile(dexFile);
                            manager.getPatchReporter().onPatchDexOptFail(patchFile, dexFile, optimizeDexDirectory, dexFile.getName(), thr);
                        }
                    }
            );
            //list again
            if (isSuccess) {
                for (File file : files) {
                    try {
                        if (!SharePatchFileUtil.isLegalFile(file)) {
                            TinkerLog.e(TAG, "single dex optimizer file %s is not exist, just return false", file);
                            return false;
                        }
                        String outputPathName = SharePatchFileUtil.optimizedPathFor(file, optimizeDexDirectoryFile);
                        File outputFile = new File(outputPathName);
                        if (!SharePatchFileUtil.isLegalFile(outputFile)) {
                            TinkerLog.e(TAG, "parallel dex optimizer file %s fail, optimize again", outputPathName);
                            long start = System.currentTimeMillis();
                            DexFile.loadDex(file.getAbsolutePath(), outputPathName, 0);
                            TinkerLog.i(TAG, "success single dex optimize file, path: %s, use time: %d", file.getPath(), (System.currentTimeMillis() - start));
                            if (!SharePatchFileUtil.isLegalFile(outputFile)) {
                                manager.getPatchReporter()
                                    .onPatchDexOptFail(patchFile, file, optimizeDexDirectory,
                                        file.getName(), new TinkerRuntimeException("dexOpt file:" + outputPathName + " is not exist"));
                                return false;
                            }
                        }
                    } catch (Throwable e) {
                        TinkerLog.e(TAG, "dex optimize or load failed, path:" + file.getPath());
                        //delete file
                        SharePatchFileUtil.safeDeleteFile(file);
                        manager.getPatchReporter().onPatchDexOptFail(patchFile, file, optimizeDexDirectory, file.getName(), e);
                        return false;
                    }
                }
            }

            return isSuccess;
        }

        return true;
    }

    private static boolean extractDexDiffInternals(Context context, String dir, String meta, File patchFile, int type) {
        //parse
        ArrayList<ShareDexDiffPatchInfo> patchList = new ArrayList<>();

        ShareDexDiffPatchInfo.parseDexDiffPatchInfo(meta, patchList);

        if (patchList.isEmpty()) {
            TinkerLog.w(TAG, "extract patch list is empty! type:%s:", ShareTinkerInternals.getTypeString(type));
            return true;
        }

        File directory = new File(dir);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        //I think it is better to extract the raw files from apk
        Tinker manager = Tinker.with(context);
        ZipFile apk = null;
        ZipFile patch = null;
        try {
            ApplicationInfo applicationInfo = context.getApplicationInfo();
            if (applicationInfo == null) {
                // Looks like running on a test Context, so just return without patching.
                TinkerLog.w(TAG, "applicationInfo == null!!!!");
                return false;
            }
            String apkPath = applicationInfo.sourceDir;
            apk = new ZipFile(apkPath);
            patch = new ZipFile(patchFile);

            for (ShareDexDiffPatchInfo info : patchList) {
                long start = System.currentTimeMillis();

                final String infoPath = info.path;
                String patchRealPath;
                if (infoPath.equals("")) {
                    patchRealPath = info.rawName;
                } else {
                    patchRealPath = info.path + "/" + info.rawName;
                }

                String dexDiffMd5 = info.dexDiffMd5;
                String oldDexCrc = info.oldDexCrC;

                if (!ShareTinkerInternals.isVmArt() && info.destMd5InDvm.equals("0")) {
                    TinkerLog.w(TAG, "patch dex %s is only for art, just continue", patchRealPath);
                    continue;
                }
                String extractedFileMd5 = ShareTinkerInternals.isVmArt() ? info.destMd5InArt : info.destMd5InDvm;

                if (!SharePatchFileUtil.checkIfMd5Valid(extractedFileMd5)) {
                    TinkerLog.w(TAG, "meta file md5 invalid, type:%s, name: %s, md5: %s", ShareTinkerInternals.getTypeString(type), info.rawName, extractedFileMd5);
                    manager.getPatchReporter().onPatchPackageCheckFail(patchFile, BasePatchInternal.getMetaCorruptedCode(type));
                    return false;
                }

                File extractedFile = new File(dir + info.realName);

                //check file whether already exist
                if (extractedFile.exists()) {
                    if (SharePatchFileUtil.verifyDexFileMd5(extractedFile, extractedFileMd5)) {
                        //it is ok, just continue
                        TinkerLog.w(TAG, "dex file %s is already exist, and md5 match, just continue", extractedFile.getPath());
                        continue;
                    } else {
                        TinkerLog.w(TAG, "have a mismatch corrupted dex " + extractedFile.getPath());
                        extractedFile.delete();
                    }
                } else {
                    extractedFile.getParentFile().mkdirs();
                }

                ZipEntry patchFileEntry = patch.getEntry(patchRealPath);
                ZipEntry rawApkFileEntry = apk.getEntry(patchRealPath);

                if (oldDexCrc.equals("0")) {
                    if (patchFileEntry == null) {
                        TinkerLog.w(TAG, "patch entry is null. path:" + patchRealPath);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }

                    //it is a new file, but maybe we need to repack the dex file
                    if (!extractDexFile(patch, patchFileEntry, extractedFile, info)) {
                        TinkerLog.w(TAG, "Failed to extract raw patch file " + extractedFile.getPath());
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }
                } else if (dexDiffMd5.equals("0")) {
                    // skip process old dex for real dalvik vm
                    if (!ShareTinkerInternals.isVmArt()) {
                        continue;
                    }

                    if (rawApkFileEntry == null) {
                        TinkerLog.w(TAG, "apk entry is null. path:" + patchRealPath);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }

                    //check source crc instead of md5 for faster
                    String rawEntryCrc = String.valueOf(rawApkFileEntry.getCrc());
                    if (!rawEntryCrc.equals(oldDexCrc)) {
                        TinkerLog.e(TAG, "apk entry %s crc is not equal, expect crc: %s, got crc: %s", patchRealPath, oldDexCrc, rawEntryCrc);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }

                    // Small patched dex generating strategy was disabled, we copy full original dex directly now.
                    //patchDexFile(apk, patch, rawApkFileEntry, null, info, smallPatchInfoFile, extractedFile);
                    extractDexFile(apk, rawApkFileEntry, extractedFile, info);

                    if (!SharePatchFileUtil.verifyDexFileMd5(extractedFile, extractedFileMd5)) {
                        TinkerLog.w(TAG, "Failed to recover dex file when verify patched dex: " + extractedFile.getPath());
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        SharePatchFileUtil.safeDeleteFile(extractedFile);
                        return false;
                    }
                } else {
                    if (patchFileEntry == null) {
                        TinkerLog.w(TAG, "patch entry is null. path:" + patchRealPath);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }

                    if (!SharePatchFileUtil.checkIfMd5Valid(dexDiffMd5)) {
                        TinkerLog.w(TAG, "meta file md5 invalid, type:%s, name: %s, md5: %s", ShareTinkerInternals.getTypeString(type), info.rawName, dexDiffMd5);
                        manager.getPatchReporter().onPatchPackageCheckFail(patchFile, BasePatchInternal.getMetaCorruptedCode(type));
                        return false;
                    }

                    if (rawApkFileEntry == null) {
                        TinkerLog.w(TAG, "apk entry is null. path:" + patchRealPath);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }
                    //check source crc instead of md5 for faster
                    String rawEntryCrc = String.valueOf(rawApkFileEntry.getCrc());
                    if (!rawEntryCrc.equals(oldDexCrc)) {
                        TinkerLog.e(TAG, "apk entry %s crc is not equal, expect crc: %s, got crc: %s", patchRealPath, oldDexCrc, rawEntryCrc);
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        return false;
                    }

                    patchDexFile(apk, patch, rawApkFileEntry, patchFileEntry, info, extractedFile);

                    if (!SharePatchFileUtil.verifyDexFileMd5(extractedFile, extractedFileMd5)) {
                        TinkerLog.w(TAG, "Failed to recover dex file when verify patched dex: " + extractedFile.getPath());
                        manager.getPatchReporter().onPatchTypeExtractFail(patchFile, extractedFile, info.rawName, type);
                        SharePatchFileUtil.safeDeleteFile(extractedFile);
                        return false;
                    }

                    TinkerLog.w(TAG, "success recover dex file: %s, use time: %d",
                            extractedFile.getPath(), (System.currentTimeMillis() - start));
                }
            }
        } catch (Throwable e) {
            throw new TinkerRuntimeException("patch " + ShareTinkerInternals.getTypeString(type) + " extract failed (" + e.getMessage() + ").", e);
        } finally {
            SharePatchFileUtil.closeZip(apk);
            SharePatchFileUtil.closeZip(patch);
        }
        return true;
    }

    /**
     * repack dex to jar
     *
     * @param zipFile
     * @param entryFile
     * @param extractTo
     * @param targetMd5
     * @return boolean
     * @throws IOException
     */
    private static boolean extractDexToJar(ZipFile zipFile, ZipEntry entryFile, File extractTo, String targetMd5) throws IOException {
        int numAttempts = 0;
        boolean isExtractionSuccessful = false;
        while (numAttempts < MAX_EXTRACT_ATTEMPTS && !isExtractionSuccessful) {
            numAttempts++;

            FileOutputStream fos = new FileOutputStream(extractTo);
            InputStream in = zipFile.getInputStream(entryFile);

            ZipOutputStream zos = null;
            BufferedInputStream bis = null;

            TinkerLog.i(TAG, "try Extracting " + extractTo.getPath());
            try {
                zos = new ZipOutputStream(new
                        BufferedOutputStream(fos));
                bis = new BufferedInputStream(in);

                byte[] buffer = new byte[ShareConstants.BUFFER_SIZE];
                ZipEntry entry = new ZipEntry(ShareConstants.DEX_IN_JAR);
                zos.putNextEntry(entry);
                int length = bis.read(buffer);
                while (length != -1) {
                    zos.write(buffer, 0, length);
                    length = bis.read(buffer);
                }
                zos.closeEntry();
            } finally {
                SharePatchFileUtil.closeQuietly(bis);
                SharePatchFileUtil.closeQuietly(zos);
            }

            isExtractionSuccessful = SharePatchFileUtil.verifyDexFileMd5(extractTo, targetMd5);
            TinkerLog.i(TAG, "isExtractionSuccessful: %b", isExtractionSuccessful);

            if (!isExtractionSuccessful) {
                extractTo.delete();
                if (extractTo.exists()) {
                    TinkerLog.e(TAG, "Failed to delete corrupted dex " + extractTo.getPath());
                }
            }
        }
        return isExtractionSuccessful;
    }

//    /**
//     * reject dalvik vm, but sdk version is larger than 21
//     */
//    private static void checkVmArtProperty() {
//        boolean art = ShareTinkerInternals.isVmArt();
//        if (!art && Build.VERSION.SDK_INT >= 21) {
//            throw new TinkerRuntimeException(ShareConstants.CHECK_VM_PROPERTY_FAIL + ", it is dalvik vm, but sdk version " + Build.VERSION.SDK_INT + " is larger than 21!");
//        }
//    }

    private static boolean extractDexFile(ZipFile zipFile, ZipEntry entryFile, File extractTo, ShareDexDiffPatchInfo dexInfo) throws IOException {
        final String fileMd5 = ShareTinkerInternals.isVmArt() ? dexInfo.destMd5InArt : dexInfo.destMd5InDvm;
        final String rawName = dexInfo.rawName;
        final boolean isJarMode = dexInfo.isJarMode;
        //it is raw dex and we use jar mode, so we need to zip it!
        if (SharePatchFileUtil.isRawDexFile(rawName) && isJarMode) {
            return extractDexToJar(zipFile, entryFile, extractTo, fileMd5);
        }
        return extract(zipFile, entryFile, extractTo, fileMd5, true);
    }

    /**
     * Generate patched dex file (May wrapped it by a jar if needed.)
     * @param baseApk
     *   OldApk.
     * @param patchPkg
     *   Patch package, it is also a zip file.
     * @param oldDexEntry
     *   ZipEntry of old dex.
     * @param patchFileEntry
     *   ZipEntry of patch file. (also ends with .dex) This could be null.
     * @param patchInfo
     *   Parsed patch info from package-meta.txt
     * @param patchedDexFile
     *   Patched dex file, may be a jar.
     *
     * <b>Notice: patchFileEntry and smallPatchInfoFile cannot both be null.</b>
     *
     * @throws IOException
     */
    private static void patchDexFile(
            ZipFile baseApk, ZipFile patchPkg, ZipEntry oldDexEntry, ZipEntry patchFileEntry,
            ShareDexDiffPatchInfo patchInfo,  File patchedDexFile) throws IOException {
        InputStream oldDexStream = null;
        InputStream patchFileStream = null;
        try {
            oldDexStream = new BufferedInputStream(baseApk.getInputStream(oldDexEntry));
            patchFileStream = (patchFileEntry != null ? new BufferedInputStream(patchPkg.getInputStream(patchFileEntry)) : null);

            final boolean isRawDexFile = SharePatchFileUtil.isRawDexFile(patchInfo.rawName);
            if (!isRawDexFile || patchInfo.isJarMode) {
                ZipOutputStream zos = null;
                try {
                    zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(patchedDexFile)));
                    zos.putNextEntry(new ZipEntry(ShareConstants.DEX_IN_JAR));
                    // Old dex is not a raw dex file.
                    if (!isRawDexFile) {
                        ZipInputStream zis = null;
                        try {
                            zis = new ZipInputStream(oldDexStream);
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                if (ShareConstants.DEX_IN_JAR.equals(entry.getName())) break;
                            }
                            if (entry == null) {
                                throw new TinkerRuntimeException("can't recognize zip dex format file:" + patchedDexFile.getAbsolutePath());
                            }
                            new DexPatchApplier(zis, (int) entry.getSize(), patchFileStream).executeAndSaveTo(zos);
                        } finally {
                            SharePatchFileUtil.closeQuietly(zis);
                        }
                    } else {
                        new DexPatchApplier(oldDexStream, (int) oldDexEntry.getSize(), patchFileStream).executeAndSaveTo(zos);
                    }
                    zos.closeEntry();
                } finally {
                    SharePatchFileUtil.closeQuietly(zos);
                }
            } else {
                new DexPatchApplier(oldDexStream, (int) oldDexEntry.getSize(), patchFileStream).executeAndSaveTo(patchedDexFile);
            }
        } finally {
            SharePatchFileUtil.closeQuietly(oldDexStream);
            SharePatchFileUtil.closeQuietly(patchFileStream);
        }
    }

}
