"""Bounded acquisition of additional public sources with integrity receipts."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
import json
from pathlib import Path
import tarfile
import time
import requests

from acquire import DATA, digest, download, extract, get_json, sanpo, save

class DownloadForm(HTMLParser):
    def __init__(self):
        super().__init__(); self.action=None; self.params={}
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='form' and a.get('id')=='download-form': self.action=a.get('action')
        if tag=='input' and a.get('type')=='hidden': self.params[a['name']]=a.get('value','')

def drive(name, ident):
    path=DATA/'archives'/f'{name}.zip'
    if not path.exists():
        with requests.Session() as s:
            r=s.get('https://drive.google.com/uc',params={'export':'download','id':ident},timeout=60)
            r.raise_for_status()
            if r.headers.get('Content-Type','').startswith('text/html'):
                form=DownloadForm();form.feed(r.text)
                if not form.action or not form.action.startswith('https://drive.usercontent.google.com/'):
                    raise ValueError('No public Drive download form; login or download unavailable')
                r=s.get(form.action,params=form.params,stream=True,timeout=90);r.raise_for_status()
            if 'text/html' in r.headers.get('Content-Type',''):
                raise ValueError('Drive returned HTML rather than archive')
            part=path.with_suffix('.part');total=0
            with part.open('wb') as f:
                for chunk in r.iter_content(1024*1024):
                    total+=len(chunk)
                    if total>3*1024**3:raise ValueError('Bounded download exceeds 3 GiB')
                    f.write(chunk)
            with part.open('rb') as archive_header:
                if archive_header.read(4)!=b'PK\x03\x04':raise ValueError('Not a ZIP archive')
            part.replace(path)
    extract(path,DATA/'raw/additional'/name)
    return {'bytes':path.stat().st_size,'sha256':digest(path),'license_status':'verify archive terms before training'}

def gonet():
    receipts=[]
    for split,remote in [('train','train'),('val','vali'),('test','test')]:
        url=f'https://cvgl.stanford.edu/gonet/dataset/dataset/data_{remote}_annotation.tar.gz'
        path=DATA/'archives'/f'gonet_{split}.tar.gz'
        # Existing files downloaded directly must still receive local SHA receipts.
        if path.exists():
            receipt={'url':url,'bytes':path.stat().st_size,'sha256':digest(path)}
        else:receipt=download(url,path)
        target=DATA/'raw/additional/gonet'/split;target.mkdir(parents=True,exist_ok=True)
        with tarfile.open(path) as archive:
            for m in archive.getmembers():
                if not (target/m.name).resolve().is_relative_to(target.resolve()) or not (m.isfile() or m.isdir()):
                    raise ValueError('Unsafe TAR member')
            archive.extractall(target,filter='data')
        receipts.append(receipt)
    return {'license':'CC BY-NC-SA 3.0','source':'https://cvgl.stanford.edu/gonet/dataset/','archives':receipts}

def gnd():
    meta=get_json('https://dataverse.orc.gmu.edu/api/datasets/:persistentId/',{'persistentId':'doi:10.13021/ORC2020/JUIW5F'})
    save(DATA/'metadata/additional/gnd.json',meta)
    version=meta['data']['latestVersion']
    files=[f['dataFile'] for f in version['files']]
    selected=[next(f for f in files if f['filename']=='UMD_map3_2_mckeldin_south.zip'),
              next(f for f in files if f['filename']=='UMD_map3.png')]
    receipts=[]
    for f in selected:
        path=DATA/'archives'/('gnd_'+f['filename'])
        receipts.append(download(f"https://dataverse.orc.gmu.edu/api/access/datafile/{f['id']}",path,f['filesize'],'md5:'+f['checksum']['value']))
        if path.suffix=='.zip':extract(path,DATA/'raw/additional/gnd')
    return {'license':version.get('license'),'files':selected,'receipts':receipts,'scope':'one processed UMD segment plus matching UMD_map3; no raw rosbags or camera photos'}

def sanpo_extra():
    pilot=json.loads((DATA/'metadata/sanpo_pilot.json').read_text())
    excluded=[s['session'].rstrip('/').split('/')[-1] for s in pilot['sessions']]
    return sanpo(sessions=12,frames=48,metadata_name='sanpo_expansion.json',excluded_sessions=excluded,training_only=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('source',choices=['gonet','ss4blind','gnd','sanpo_extra']);args=parser.parse_args()
    state=DATA/'status'/f'additional_{args.source}.json';save(state,{'state':'running','started':time.time()})
    try:
        if args.source=='gonet':receipt=gonet()
        elif args.source=='gnd':receipt=gnd()
        elif args.source=='sanpo_extra':receipt=sanpo_extra()
        else:
            items=[('ss4blind_curbs','1D0ji8wQUXsSiCOBntbzQByTdfmSpi2sG'),('ss4blind_terrain','1BUl6fOE15jnoqY2Ioop-vaQLgC8gdXvY'),('ss4blind_rgbdss','1QcSBWDkQ1qeljWsVHbtvV3xMhBhEL5a6')]
            receipt={}
            for name,ident in items:
                try:receipt[name]=drive(name,ident);print(name,'downloaded and verified',flush=True)
                except Exception as error:receipt[name]={'error':str(error)}
            if all('error' in r for r in receipt.values()):raise ValueError(receipt)
        save(state,{'state':'complete','finished':time.time(),'receipt':receipt});print(args.source,'complete',flush=True)
    except Exception as error:
        save(state,{'state':'failed','error':str(error),'finished':time.time()});raise

if __name__=='__main__':main()
