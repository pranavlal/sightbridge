"""Research-only traversability classifier on published GO Stanford small splits."""
from collections import Counter,defaultdict
import hashlib,json,re
from pathlib import Path
from itertools import combinations
from PIL import Image
import numpy as np
import torch
from torch import nn
from acquire import DATA,digest,save
import run_detection_study as detector
from study_manifest import annotate_image

OUT=DATA/'additional_study';OUT.mkdir(exist_ok=True)

def score(y,p,threshold):
    pred=p>=threshold;tp=int(np.sum(pred & (y==1)));fp=int(np.sum(pred & (y==0)))
    fn=int(np.sum(~pred & (y==1)));tn=int(np.sum(~pred & (y==0)))
    recall=tp/max(1,tp+fn);specificity=tn/max(1,tn+fp)
    return dict(f1=2*tp/max(1,2*tp+fp+fn),unsafe_recall=recall,
                false_alarm_fraction=fp/max(1,fp+tn),balanced_accuracy=(recall+specificity)/2,
                tp=tp,fp=fp,fn=fn,tn=tn)

def main():
    torch.set_num_threads(4);torch.set_num_interop_threads(1)
    records=[]
    for split in ['train','val','test']:
        for path in sorted((DATA/'raw/additional/gonet'/split).rglob('*.jpg')):
            unsafe=int(path.parent.name.startswith('negative'))
            group=split+':'+re.sub(r'_[LR]_', '_stereo_',path.stem)
            r=dict(image=str(path.relative_to(DATA)),source='gonet',split=split,
                   group=group,unsafe=unsafe,objects=[],supervised=[])
            r=annotate_image(r);r.pop('_thumbnail');r['id']='gonet-'+hashlib.sha256(r['image'].encode()).hexdigest()[:20]
            records.append(r)
    byhash=defaultdict(list)
    for r in records:byhash[r['sha256']].append(r)
    remove=set()
    for group in byhash.values():
        if len({r['unsafe'] for r in group})>1:
            remove.update(r['id'] for r in group);continue
        priority={'test':0,'val':1,'train':2};best=min(priority[r['split']] for r in group)
        remove.update(r['id'] for r in group if priority[r['split']]>best)
    by_dhash=defaultdict(list)
    for r in records:by_dhash[r['dhash']].append(r)
    near_links=[]
    for group in by_dhash.values():
        for a,b in combinations(group,2):
            if a['split']==b['split']:continue
            thumbs=[]
            for r in [a,b]:
                with Image.open(DATA/r['image']) as im:
                    thumbs.append(np.asarray(im.convert('RGB').resize((32,32),Image.Resampling.BILINEAR)).astype(float))
            mae=float(np.mean(np.abs(thumbs[0]-thumbs[1])))
            if mae<=5:
                near_links.append(dict(a=a['id'],b=b['id'],mae=mae,splits=[a['split'],b['split']]))
                priority={'test':0,'val':1,'train':2}
                remove.add((a if priority[a['split']]>priority[b['split']] else b)['id'])
    save(OUT/'gonet_near_audit.json',dict(cross_split_links=near_links,method='equal dHash and RGB thumbnail MAE <=5; conservative, not exhaustive'))
    # Stereo mates with matching identifiers remain together in the provided splits.
    groups=defaultdict(list)
    for r in records:groups[r['group']].append(r)
    for group in groups.values():
        if len({r['split'] for r in group})>1:
            best=min({'test':0,'val':1,'train':2}[r['split']] for r in group)
            remove.update(r['id'] for r in group if {'test':0,'val':1,'train':2}[r['split']]>best)
    records=[r for r in records if r['id'] not in remove]
    if any(not any(r['split']==split for r in records) for split in ['train','val','test']):
        raise ValueError('Empty published split after overlap quarantine')
    protocol=dict(task='unsafe vs traversable scene classification, not hazard localization',seeds=[17,29,43],
        model='COCO SSDLite MobileNetV3 frozen six-level pooled features, 64-unit MLP classifier',
        epochs=60,batch_size=128,learning_rate=.001,checkpoint='best validation F1; validation threshold',
        excluded_overlap_images=len(remove),near_duplicate_audit=f'Exact hashes plus equal dHash/RGB thumbnail MAE<=5 audited; {len(near_links)} cross-split links; not exhaustive',
        license='CC BY-NC-SA 3.0; research only; not deployed',test_unit='stereo identifier; average mate probabilities',
        limitations=['labelled unsafe scenes include obstacles, not just drop-offs','building/route metadata unavailable in small split filenames'])
    save(OUT/'gonet_protocol.json',protocol);save(OUT/'gonet_manifest.json',records)
    print('GONet split counts',dict(Counter(r['split'] for r in records)),'removed',len(remove),flush=True)
    base=detector.pretrained();detector.cache_features(base,records);features=detector.load_features(records)
    x=torch.stack([torch.cat([f.float().mean((-2,-1)) for f in features[r['id']]]) for r in records])
    y=torch.tensor([r['unsafe'] for r in records],dtype=torch.float32)
    indices={s:torch.tensor([i for i,r in enumerate(records) if r['split']==s]) for s in ['train','val','test']}
    mu=x[indices['train']].mean(0);sd=x[indices['train']].std(0).clamp(min=.01);x=(x-mu)/sd
    def grouped(split,p):
        values=defaultdict(list);truth={}
        for i,prob in zip(indices[split].tolist(),p.tolist()):values[records[i]['group']].append(prob);truth[records[i]['group']]=records[i]['unsafe']
        keys=sorted(values);return np.array([truth[k] for k in keys]),np.array([np.mean(values[k]) for k in keys]),keys
    results=[]
    for seed in protocol['seeds']:
        torch.manual_seed(seed);model=nn.Sequential(nn.Linear(x.shape[1],64),nn.ReLU(),nn.Linear(64,1))
        optimizer=torch.optim.AdamW(model.parameters(),lr=.001);best=-1;best_state=None;best_t=.5
        for epoch in range(60):
            model.train();permutation=indices['train'][torch.randperm(len(indices['train']))]
            for j in range(0,len(permutation),128):
                batch=permutation[j:j+128];optimizer.zero_grad();loss=nn.functional.binary_cross_entropy_with_logits(model(x[batch]).flatten(),y[batch]);loss.backward();optimizer.step()
            model.eval()
            with torch.no_grad():valp=torch.sigmoid(model(x[indices['val']]).flatten())
            vy,vp,_=grouped('val',valp)
            t=max(np.linspace(.05,.95,37),key=lambda t:score(vy,vp,t)['f1']);v=score(vy,vp,t)['f1']
            if v>best:best=v;best_t=float(t);best_state={k:v.clone() for k,v in model.state_dict().items()};best_epoch=epoch+1
        model.load_state_dict(best_state)
        with torch.no_grad():tp=torch.sigmoid(model(x[indices['test']]).flatten())
        ty,probs,keys=grouped('test',tp);metrics=score(ty,probs,best_t)
        results.append(dict(seed=seed,threshold=best_t,best_epoch=best_epoch,test=metrics,test_groups=len(keys)))
        save(OUT/f'gonet_predictions_{seed}.json',dict(groups=keys,truth=ty.tolist(),scores=probs.tolist(),threshold=best_t))
        torch.save(dict(state=best_state,mean=mu,std=sd,threshold=best_t),OUT/f'gonet_classifier_{seed}.pt')
        print(seed,metrics,flush=True)
    save(OUT/'gonet_results.json',dict(protocol=protocol,split_counts=dict(Counter(r['split'] for r in records)),results=results,
        means={k:float(np.mean([r['test'][k] for r in results])) for k in ['f1','unsafe_recall','false_alarm_fraction','balanced_accuracy']}))

if __name__=='__main__':main()
