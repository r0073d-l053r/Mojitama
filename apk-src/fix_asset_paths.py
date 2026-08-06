"""aapt2 on Windows writes asset entries with backslashes (assets/web\\index.html).
AssetManager looks them up with forward slashes, so the app would 404 on every asset.
Rewrite the archive with normalized names, preserving each entry's compression
(resources.arsc must stay STORED for targetSdk >= 30)."""
import sys, zipfile, shutil, os

src, dst = sys.argv[1], sys.argv[2]
fixed = []
with zipfile.ZipFile(src, 'r') as zin:
    infos = zin.infolist()
    with zipfile.ZipFile(dst, 'w') as zout:
        for i in infos:
            data = zin.read(i.filename)
            name = i.filename.replace('\\', '/')
            if name != i.filename:
                fixed.append(name)
            ni = zipfile.ZipInfo(name, date_time=i.date_time)
            ni.compress_type = i.compress_type
            ni.external_attr = i.external_attr
            ni.internal_attr = i.internal_attr
            ni.create_system = i.create_system
            zout.writestr(ni, data)

print("normalized %d entr%s" % (len(fixed), "y" if len(fixed) == 1 else "ies"))
for f in fixed:
    print("   ", f)
