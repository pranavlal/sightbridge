"""Paired SANPO expansion ablation plus independent SS4Blind source evaluation."""
from collections import Counter,defaultdict
import hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from torchvision.models.detection.image_list import ImageList
from acquire import DATA,digest,save
from study_manifest import annotate_image,components,row
from detection_metrics import summarize,match,counts
import run_detection_study as detector

OLD=DATA/'study';NEW=DATA/'additional_study'
SEEDS=[17,29,43]

def prepare():
    NEW.mkdir(exist_ok=True)
    baseline=[json.loads(l) for l in (OLD/'manifest.jsonl').read_text().splitlines()]
    expansion=json.loads((DATA/'metadata/sanpo_expansion.json').read_text())
    official_train=set((DATA/'raw/sanpo/sanpo-real/splits/train_session_ids.txt').read_text().splitlines())
    seen={r['sha256'] for r in baseline};heldout_sessions={r['group'].split(':',1)[1] for r in baseline if r['source']=='sanpo'}
    additional=[];excluded=[]
    for session in expansion['sessions']:
        sid=session['session'].rstrip('/').split('/')[-1]
        if sid not in official_train or sid in heldout_sessions:raise ValueError('SANPO session overlap or wrong official split')
        for obj in session['objects']:
            if '/segmentation_masks/' not in obj['name'] or not obj['name'].endswith('.png'):continue
            mask=DATA/'raw/sanpo'/obj['name'].removeprefix('sanpo_dataset/v0/')
            image=mask.parent.parent/'video_frames'/mask.name
            with Image.open(mask) as im:a=np.asarray(im)[:,:,0]
            r=annotate_image(row(image,'sanpo_extra',[2,3],'sanpo:'+sid,'train',components(a==2,3,50)+components(a==15,2,50)))
            r['id']='sanpo-extra-'+hashlib.sha256(r['image'].encode()).hexdigest()[:20]
            # Conservative perceptual duplicate checks against every original split.
            near=False
            for old in baseline:
                if old['dhash']!=r['dhash'] or abs((r['width']/r['height'])/(old['width']/old['height'])-1)>.03:continue
                with Image.open(DATA/old['image']) as im:thumb=np.asarray(im.convert('RGB').resize((32,32),Image.Resampling.BILINEAR))
                if np.mean(np.abs(thumb.astype(float)-r['_thumbnail'].astype(float)))<=5:near=True;break
            if r['sha256'] in seen or near:excluded.append(r['image']);continue
            r.pop('_thumbnail');seen.add(r['sha256']);additional.append(r)
    if not additional:raise ValueError('No usable SANPO additions')
    records=baseline+additional
    (NEW/'manifest.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
    protocol=json.loads((OLD/'protocol.json').read_text());protocol.update(arms=['augmented'],
        baseline='Existing combined dataset runs with the same three initializations and update budget',
        combined_sampling='20% new official-training SANPO; 80% previous combined sampler (40% barriers, 60% other)',
        primary_comparison='augmented minus previous combined, same original 3418 test images',
        original_manifest_sha256=digest(OLD/'manifest.jsonl'),original_protocol_sha256=digest(OLD/'protocol.json'),
        added_images=len(additional),added_sessions=len({r['group'] for r in additional}),
        added_objects=dict(Counter(str(o['label']) for r in additional for o in r['objects'])),
        excluded_additions=excluded,checkpoint_selection_labels=[1,2,3,5],
        ss4blind_policy='external-source evaluation only; no training or validation tuning on these images; license not established')
    save(NEW/'protocol.json',protocol)
    for name,target in [('features',OLD/'features'),('pretrained_ssdlite.pth',OLD/'pretrained_ssdlite.pth')]:
        p=NEW/name
        if not p.exists():p.symlink_to(target.resolve(),target_is_directory=target.is_dir())
    print('SANPO additions',protocol['added_images'],'sessions',protocol['added_sessions'],'objects',protocol['added_objects'],flush=True)
    return records,protocol

def choose_batch(pool,arm,rng,size):
    old={k:v for k,v in pool.items() if k!='sanpo_extra'}
    batch=[]
    for _ in range(size):
        if rng.random()<.2:batch.append(pool['sanpo_extra'][rng.integers(len(pool['sanpo_extra']))])
        else:batch.extend(ORIGINAL_SAMPLER(old,'combined',rng,1))
    return batch

ORIGINAL_SAMPLER=detector.choose_batch

def analyze(records,external):
    test=[r for r in records if r['split']=='test']
    keys=[]
    for r in test:
        key=(r['source'],r.get('evaluation_cluster') if r['source'] in ['wet_potholes','gutters'] else r['group'])
        if key not in keys:keys.append(key)
        r['_group']=keys.index(key)
    arr=np.zeros((2,3,len(keys),3,3));metrics={};external_results={}
    for ai,arm in enumerate(['combined','augmented']):
        root=OLD if arm=='combined' else NEW;metrics[arm]=[];external_results[arm]=[]
        for si,seed in enumerate(SEEDS):
            p=root/'runs'/f'{arm}-{seed}';result=json.loads((p/'complete.json').read_text());thresholds=json.loads((p/'validation.json').read_text())['thresholds']
            predictions=json.loads((p/'test_predictions.json').read_text());recalc,_=summarize(test,predictions,[1,2,3,4,5],thresholds)
            for label in recalc:
                for k in ['tp','fp','fn','f1','ap50']:
                    if recalc[label][k] is not None and not np.isclose(recalc[label][k],result['test'][label][k]):raise ValueError('Independent metric recalculation mismatch')
            metrics[arm].append(result['test'])
            external_results[arm].append(json.loads((NEW/f'ss4blind_{arm}_{seed}.json').read_text()))
            for r in test:
                for label in [1,2,3]:
                    if label not in r['supervised']:continue
                    scores,correct,ntruth=match(predictions[r['id']],r['objects'],label)
                    arr[ai,si,r['_group'],label-1]+=counts(scores,correct,ntruth,thresholds[str(label)])
    def f1(a):
        tp,fp,fn=np.moveaxis(a,-1,0);return 2*tp/np.maximum(1,2*tp+fp+fn)
    point=f1(arr.sum(2));delta=float((point[1].mean(1)-point[0].mean(1)).mean())
    bysource=defaultdict(list)
    for i,(source,_) in enumerate(keys):bysource[source].append(i)
    rng=np.random.default_rng(20261012);samples=[]
    for _ in range(2000):
        weights=np.zeros(len(keys))
        for group in bysource.values():weights+=np.bincount(rng.choice(group,len(group),replace=True),minlength=len(keys))
        seeds=rng.integers(0,3,3);v=f1((arr*weights[None,None,:,None,None]).sum(2))
        samples.append(float((v[1,seeds]-v[0,seeds]).mean()))
    interval=np.percentile(samples,[2.5,97.5]).tolist()
    classes={str(c):{arm:{metric:float(np.mean([r[str(c)][metric] for r in metrics[arm]])) for metric in ['f1','ap50','precision','recall']} for arm in metrics} for c in [1,2,3]}
    oldsha=digest(OLD/'manifest.jsonl');protocol=json.loads((NEW/'protocol.json').read_text())
    if oldsha!=protocol['original_manifest_sha256']:raise ValueError('Original holdout manifest changed')
    save(NEW/'comparison.json',dict(test_images=len(test),classes=classes,macro_f1={arm:float(point[ai].mean()) for ai,arm in enumerate(['combined','augmented'])},
        delta=delta,exploratory_95_interval=interval,meaningful_gain=delta>=.05 and interval[0]>0,ss4blind=external_results,protocol=protocol,
        caveat='Source-stratified seed/group interval conditional on small original collection groups; no walking safety or descending-edge claim'))
    print('Accuracy comparison',delta,interval,flush=True)

def main():
    torch.set_num_threads(4);torch.set_num_interop_threads(1)
    records,protocol=prepare();external=json.loads((NEW/'ss4blind_manifest.json').read_text())
    detector.STUDY=NEW;detector.choose_batch=choose_batch
    base=detector.pretrained();detector.cache_features(base,records+external);features=detector.load_features(records+external)
    sample=[f.unsqueeze(0).float() for f in features[records[0]['id']]]
    anchors=base.anchor_generator(ImageList(torch.zeros(1,3,320,320),[(320,320)]),sample)[0]
    targets=detector.targets_and_matches(base,records,anchors)
    for seed in SEEDS:
        save(NEW/'status.json',{'state':'training','seed':seed})
        detector.train_arm(base,records,features,targets,anchors,'augmented',seed,protocol)
        oldinitial=torch.load(OLD/'runs'/f'combined-{seed}'/'initial.pt',weights_only=True)
        newinitial=torch.load(NEW/'runs'/f'augmented-{seed}'/'initial.pt',weights_only=True)
        if any(not torch.equal(v,newinitial[k]) for k,v in oldinitial.items()):raise ValueError('Different paired initialization')
        for arm,root in [('combined',OLD),('augmented',NEW)]:
            directory=root/'runs'/f'{arm}-{seed}';head=detector.make_head(base,seed);head.load_state_dict(torch.load(directory/'best.pt',weights_only=True))
            thresholds=json.loads((directory/'validation.json').read_text())['thresholds']
            predictions=detector.predict(base,head,external,features,anchors,[1,2,3,4,5])
            save(NEW/f'ss4blind_predictions_{arm}_{seed}.json',predictions)
            result={}
            for source in sorted({r['source'] for r in external}):
                subset=[r for r in external if r['source']==source];labels=sorted({l for r in subset for l in r['supervised']})
                result[source],_=summarize(subset,predictions,labels,thresholds)
            save(NEW/f'ss4blind_{arm}_{seed}.json',result)
    analyze(records,external);save(NEW/'status.json',{'state':'complete'});

if __name__=='__main__':main()
