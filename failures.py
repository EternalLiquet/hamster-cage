import glob, xml.etree.ElementTree as ET
count = 0
for path in glob.glob('app/build/outputs/androidTest-results/connected/**/*.xml', recursive=True):
    for case in ET.parse(path).getroot().iter('testcase'):
        for f in list(case.findall('failure')) + list(case.findall('error')):
            count += 1
            text = (f.text or f.get('message') or '').replace('\n', ' | ')[:3800]
            if count <= 10:
                print(f"::error title=DEVICETEST {case.get('classname')}.{case.get('name')}::{text}")
print(f'failures: {count}')
