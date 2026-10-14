"""Decode additional masks and quarantine overlaps without changing original splits."""
from collections import Counter,defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image
from acquire import DATA,digest,save
from study_manifest import annotate_image,components,row

OUT=DATA/'additional_study'

def build():
    OUT.mkdir(exist_ok=True)
    old=[json.loads(l) for l in (DATA/'study/manifest.jsonl').read_text().splitlines()]
    oldhash={r['sha256'] for r in old};olddhash=defaultdict(list)
    for r in old:olddhash[r['dhash']].append(r)
    new=[];inventories={}
    specifications=[('ss4blind_curbs','Curbs Dataset','groundtruth','left',[196,196,196],3),
                    ('ss4blind_rgbdss','RGB-D-SS Dataset','groundtruth','rgb',[196,196,196],3),
                    ('ss4blind_terrain','terrain awareness dataset','annotation','color',[255,128,0],2)]
    for name,folder,maskdir,imagedir,color,label in specifications:
        root=DATA/'raw/additional'/name/folder;count=0;pixels=0
        for mask in sorted((root/maskdir).rglob('*.png')):
            image=root/imagedir/mask.relative_to(root/maskdir)
            if not image.exists():raise ValueError(f'Missing image for {mask}')
            with Image.open(mask) as im:maskarray=np.asarray(im.convert('RGB'))
            with Image.open(image) as im:
                if im.size!=(maskarray.shape[1],maskarray.shape[0]):raise ValueError('Mask dimension mismatch')
            binary=np.all(maskarray==color,axis=-1);pixels+=int(binary.sum());count+=1
            group=name+':'+str(mask.relative_to(root/maskdir).parent)
            objects=components(binary,label,50)
            new.append(row(image,name,[label],group,'external_test',objects,mask=str(mask.relative_to(DATA)),
                license_status='archive contains no explicit license; internal research evaluation only',
                supervision_note='semantic-mask connected components converted to bounding boxes; not original detection boxes'))
        inventories[name]={'matched_images':count,'label_pixels':pixels,'palette':color,'label':label}
    with ThreadPoolExecutor(max_workers=4) as pool:new=list(pool.map(annotate_image,new))
    removed=[];seenhash=set();seendhash=defaultdict(list);retained=[]
    def near(r,others):
        for other in others:
            ratio=r['width']/r['height'];oratio=other['width']/other['height']
            if abs(ratio/oratio-1)>.03:continue
            a=other.get('_thumbnail')
            if a is None:
                with Image.open(DATA/other['image']) as im:a=np.asarray(im.convert('RGB').resize((32,32),Image.Resampling.BILINEAR))
            if np.mean(np.abs(r['_thumbnail'].astype(float)-a.astype(float)))<=5:return True
        return False
    for r in new:
        reason='exact overlap' if r['sha256'] in oldhash or r['sha256'] in seenhash else 'conservative near overlap' if near(r,olddhash[r['dhash']]+seendhash[r['dhash']]) else None
        if reason:removed.append({'image':r['image'],'reason':reason});continue
        r['id']='additional-'+hashlib.sha256(r['image'].encode()).hexdigest()[:20]
        retained.append(r);seenhash.add(r['sha256']);seendhash[r['dhash']].append(r)
    for r in retained:r.pop('_thumbnail',None)
    save(OUT/'ss4blind_manifest.json',retained)
    save(OUT/'ss4blind_audit.json',dict(inventories=inventories,retained=dict(Counter(r['source'] for r in retained)),
        excluded=removed,objects=dict(Counter(str(o['label']) for r in retained for o in r['objects'])),
        original_manifest_sha256=digest(DATA/'study/manifest.jsonl'),license='No explicit archive reuse terms found; not approved for deployment',
        palette_provenance='RGB-D-SS Labelled Classes.txt defines curb RGB196; visually confirmed same curb coding in Curbs Dataset; terrain annotation label.txt defines stairs orange'))
    print('SS4Blind retained',Counter(r['source'] for r in retained),'quarantined',len(removed),flush=True)
    return retained

if __name__=='__main__':build()
