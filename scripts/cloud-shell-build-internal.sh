#!/usr/bin/env bash
# Run manually in Google Cloud Shell. Installs tools only in this checkout.
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
export BLESK_TOOLS_DIR="${BLESK_TOOLS_DIR:-$project_dir/.local-tools}"
mkdir -p "$BLESK_TOOLS_DIR"
python3 - <<'PY'
import hashlib,json,os,tarfile,urllib.request,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(os.environ['BLESK_TOOLS_DIR'])
def download(url,dest):
    with urllib.request.urlopen(url,timeout=120) as response, dest.open('wb') as out:
        while block:=response.read(1024*1024):out.write(block)
def digest(path,algorithm):
    h=hashlib.new(algorithm)
    with path.open('rb') as stream:
        while block:=stream.read(1024*1024):h.update(block)
    return h.hexdigest()
if not (root/'jdk21/bin/java').exists():
    url='https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse'
    with urllib.request.urlopen(url,timeout=60) as response:assets=json.load(response)
    package=assets[0]['binary']['package']; archive=root/'jdk21.tar.gz'
    download(package['link'],archive)
    if digest(archive,'sha256')!=package['checksum']:raise RuntimeError('JDK checksum mismatch')
    with tarfile.open(archive) as bundle:
        top=bundle.getmembers()[0].name.split('/')[0]
        bundle.extractall(root,filter='data')
    (root/top).rename(root/'jdk21');archive.unlink()
sdk=root/'android-sdk'
if not (sdk/'cmdline-tools/latest/bin/sdkmanager').exists():
    base='https://dl.google.com/android/repository/'
    with urllib.request.urlopen(base+'repository2-3.xml',timeout=60) as response:tree=ET.parse(response)
    package=next(x for x in tree.getroot() if x.tag.endswith('remotePackage') and x.attrib.get('path')=='cmdline-tools;latest')
    archive=next(x for x in package.findall('./archives/archive') if x.findtext('host-os')=='linux').find('complete')
    local=root/'android-cli.zip';download(base+archive.findtext('url'),local)
    checksum=archive.find('checksum');algorithm=checksum.attrib.get('type','sha1')
    if digest(local,algorithm)!=checksum.text:raise RuntimeError('Android CLI checksum mismatch')
    (sdk/'cmdline-tools').mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(local) as bundle:bundle.extractall(sdk/'cmdline-tools')
    (sdk/'cmdline-tools/cmdline-tools').rename(sdk/'cmdline-tools/latest');local.unlink()
    for path in (sdk/'cmdline-tools/latest/bin').iterdir():path.chmod(path.stat().st_mode|0o111)
print('JDK21 and official Android command-line tools verified.')
PY
export JAVA_HOME="$BLESK_TOOLS_DIR/jdk21"
export ANDROID_HOME="$BLESK_TOOLS_DIR/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
echo 'Nasledujú licencie Android SDK. Prečítajte ich a potvrďte sami.'
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-36.1' 'build-tools;36.0.0' 'platform-tools'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
python3 - <<'PY'
import base64,json,subprocess,urllib.request
from pathlib import Path
project='gen-lang-client-0115180408'
app='1:356987646276:android:45325d3dea6c248380e711'
token=subprocess.run(['gcloud','auth','print-access-token'],capture_output=True,text=True,check=True).stdout.strip()
url=f'https://firebase.googleapis.com/v1beta1/projects/{project}/androidApps/{app}/config'
request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'x-goog-user-project':project})
with urllib.request.urlopen(request,timeout=30) as response:data=json.load(response)
config=json.loads(base64.b64decode(data['configFileContents']))
if config['project_info']['project_id']!=project:raise RuntimeError('Unexpected Firebase project')
if not any(c['client_info']['android_client_info']['package_name']=='sk.bizagent.bleskovafaktura' for c in config['client']):raise RuntimeError('Unexpected package')
Path('app/google-services.json').write_text(json.dumps(config))
print('Correct Firebase Android configuration downloaded; credentials not printed.')
PY
exec bash scripts/build-internal.sh "$@"
