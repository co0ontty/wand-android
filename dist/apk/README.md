# Local APK Artifacts

Put locally built Android test packages in this directory.

`debug.sh` writes files here automatically:

```bash
./debug.sh
SKIP_INSTALL=1 ./debug.sh
```

The generated file name is:

```text
wand-vX.Y.Z-debug.MMDDHHMM.apk
```

Point the wand server at this directory from `config.json`:

```json
{
  "android": {
    "enabled": true,
    "apkDir": "/absolute/path/to/wand/android/dist/apk",
    "currentApkFile": ""
  }
}
```

APK files are ignored by git; keep only this README in the repository.
