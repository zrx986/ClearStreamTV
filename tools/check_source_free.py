"""Release gate for the source-free repository (Python 3.10+)."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
problems = []
files = []
for path in root.rglob('*'):
    relative = path.relative_to(root)
    if not path.is_file() or any(part in {'.git', '.gradle', 'build', '__pycache__'} for part in relative.parts):
        continue
    files.append(relative)
    name = relative.as_posix()
    if path.name in {'local.properties', '.env', 'hosts.yml'} or path.suffix in {'.jks', '.keystore', '.db', '.apk', '.aab', '.log'}:
        problems.append(f'Private or generated file: {name}')
    if path.suffix in {'.m3u', '.m3u8', '.txt'} and not name.startswith('app/src/androidTest/assets/fixture/'):
        problems.append(f'Unexpected source/data file: {name}')
    if path.suffix not in {'.java', '.js', '.json', '.xml', '.md', '.gradle', '.properties', '.py', '.bat'}:
        continue
    text = path.read_text(encoding='utf-8-sig')
    if re.search(r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)', text):
        problems.append(f'Possible credential: {name}')
    if re.search(r'[A-Z]:[\\/](?:Users[\\/]|CodexData[\\/]|小说工作[\\/])', text):
        problems.append(f'Local machine path: {name}')
    if name.startswith(('app/src/main/', 'app/src/standard/', 'core/src/main/')):
        if re.search(r'https?://(?:nnyy\.pro|(?:www\.)?cppyy\.com|(?:www\.)?bmmyy\.com)(?:/|["\'])', text):
            problems.append(f'Legacy preset website: {name}')
        if 'cctv-channels-provenance' in name or 'CctvLiveChannels' in text:
            problems.append(f'Preinstalled channel catalog: {name}')

readme = (root/'README.md').read_text(encoding='utf-8')
for target in re.findall(r'!\[[^\]]*\]\(([^)]+)\)', readme):
    if not (root/target).is_file():
        problems.append(f'Missing README screenshot: {target}')

if problems:
    print('\n'.join(problems))
    sys.exit(1)
print(f'Source-free repository gate passed: {len(files)} files; no preset catalogs, private files, credentials or local paths found; README images present.')
