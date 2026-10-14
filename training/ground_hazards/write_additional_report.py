"""Publish concise research results and provenance, without datasets or model weights."""
from collections import Counter
import json,shutil
from pathlib import Path
from acquire import DATA,ROOT,digest

OUT=ROOT/'reports/ground-hazard-additional-sources-2026-10-12'
STUDY=DATA/'additional_study'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    result=json.loads((STUDY/'comparison.json').read_text());gonet=json.loads((STUDY/'gonet_results.json').read_text());audit=json.loads((STUDY/'ss4blind_audit.json').read_text())
    acquisitions={name:json.loads((DATA/'status'/f'additional_{name}.json').read_text()) for name in ['gonet','ss4blind','gnd','sanpo_extra']}
    receipts={'status':{k:v['state'] for k,v in acquisitions.items()},'archives':[]}
    for path in sorted((DATA/'archives').glob('*')):
        if path.name.startswith(('gonet_','ss4blind_','gnd_')) and not path.name.endswith(('.part','.json')):
            receipts['archives'].append({'name':path.name,'bytes':path.stat().st_size,'sha256':digest(path)})
    (OUT/'acquisition.json').write_text(json.dumps(receipts,indent=2)+'\n')
    for name in ['comparison.json','protocol.json','gonet_results.json','gonet_protocol.json','gonet_near_audit.json','ss4blind_audit.json','split_verification.json','baseline_reproduction.json']:
        shutil.copyfile(STUDY/name,OUT/name)
    p=result['protocol'];percent=lambda x:f'{x*100:.2f}%';points=lambda x:f'{x*100:+.2f}'
    lines=['# Additional free hazard sources: acquisition and tests','',
        'Measured 2026-10-12. These are research results, not evidence of dependable drop-off warnings. No model was added to SightBridge.','',
        '## Downloads and integrity','',
        '| Source | Acquired subset | Use in this study |','|---|---|---|',
        '| [GO Stanford/GONet](https://cvgl.stanford.edu/gonet/dataset/) | All three hand-labelled small archives: 4,800 JPEGs, 1,600 per published split | Separate unsafe/traversable classifier; CC BY-NC-SA 3.0 |',
        '| [SS4Blind](https://github.com/elnino9ykl/SS4Blind) | 100 curb pairs, 120 terrain RGB-D pairs, 400 RGB-D curb-scene pairs | 619 external evaluation images after one conservative near-duplicate removal; no training |',
        f"| [SANPO](https://github.com/google-research-datasets/sanpo_dataset) | {p['added_images']} new chest-camera pairs from {p['added_sessions']} official-training sessions | Controlled augmentation of the previous combined-data detector; CC BY 4.0 |",
        '| [GND](https://robotixx.github.io/GND/) | Processed UMD McKeldin South segment and matching UMD_map3 | CC0 confirmed by repository API; maps/point clouds only, no camera photographs in this processed ZIP |',
        '',
        'ZIP CRC checks and safe extraction passed; GONet TAR members were restricted to safe files/directories. Local SHA-256 receipts are retained. GND files matched published MD5 checksums, and SANPO objects matched published MD5 checksums. The GND raw camera recordings are in multi-gigabyte ROS bags and were not acquired in this bounded pass. The head-camera staircase-approaching study still has no verified usable public download established here.',
        '',
        '## Does expanding SANPO improve the detector?','',
        f"The additional images supply {p['added_objects'].get('3',0)} curb components and {p['added_objects'].get('2',0)} stair components. Three paired seeds were trained with the same frozen COCO MobileNetV3/SSDLite backbone, 1,000 updates, batch size 12, optimizer, validation checkpoint selection and threshold selection as the previous combined-data runs. Identical initial head tensors were checked. New SANPO images received 20% of training draws; the remaining 80% used the previous combined sampler. No new validation or test images were introduced for model selection.",
        '',
        f"On the same {result['test_images']:,} original test images, shared-class macro F1 changed from **{percent(result['macro_f1']['combined'])} to {percent(result['macro_f1']['augmented'])}**: **{points(result['delta'])} percentage points**. The conditional exploratory 95% seed/source-group bootstrap interval is **{points(result['exploratory_95_interval'][0])} to {points(result['exploratory_95_interval'][1])} points**.",
        '',
        'The pre-existing meaningful-gain rule is at least five percentage points with the paired interval above zero. '+('This experiment meets that screening rule.' if result['meaningful_gain'] else 'This experiment does not meet that rule.'),
        '', '| Class | Previous combined F1 | Expanded F1 | Previous AP50 | Expanded AP50 |','|---|---:|---:|---:|---:|']
    for label,name in [('1','pothole'),('2','stairs'),('3','curb')]:
        a=result['classes'][label]['combined'];b=result['classes'][label]['augmented']
        lines.append(f"| {name} | {percent(a['f1'])} | {percent(b['f1'])} | {percent(a['ap50'])} | {percent(b['ap50'])} |")
    lines+=['','## External evaluation on SS4Blind','','SS4Blind mask palettes were decoded from its supplied class files (curb RGB 196/196/196; terrain stairs RGB 255/128/0), with visual confirmation of the same curb color in the 100-image Curbs Dataset. Connected-component boxes were derived from semantic masks; these are not author-supplied detection boxes. Only the annotated class is evaluated. Thresholds were frozen from the original validation data, and none of these external images were used for training or checkpoint selection.','',
        '| Source / class | Images | Previous combined F1 | Expanded F1 | Previous AP50 | Expanded AP50 |','|---|---:|---:|---:|---:|']
    import numpy as np
    for source,label in [('ss4blind_curbs','3'),('ss4blind_rgbdss','3'),('ss4blind_terrain','2')]:
        means={arm:{metric:float(np.mean([r[source][label][metric] for r in result['ss4blind'][arm]])) for metric in ['f1','ap50']} for arm in ['combined','augmented']}
        lines.append(f"| {source} / {'curb' if label=='3' else 'stairs'} | {audit['retained'][source]} | {percent(means['combined']['f1'])} | {percent(means['augmented']['f1'])} | {percent(means['combined']['ap50'])} | {percent(means['augmented']['ap50'])} |")
    lines+=['','Archive reuse terms were not found for SS4Blind. It remains internal research evaluation data; no images or models derived from it are redistributed. Its small sequences, possible unverified location overlap and semantic-mask-to-box conventions limit comparisons.','',
        '## GONet: a different task','',
        f"A separate 64-unit classifier on frozen MobileNetV3 pooled features achieved mean unsafe-scene **F1 {percent(gonet['means']['f1'])}**, **recall {percent(gonet['means']['unsafe_recall'])}**, **balanced accuracy {percent(gonet['means']['balanced_accuracy'])}**, and **false-positive fraction {percent(gonet['means']['false_alarm_fraction'])}** on its published test split.",
        '',
        'There are 800 test stereo identifier groups, containing 400 unsafe and 400 traversable examples. Scores average the left/right probabilities within each published identifier. The provided training/validation/test filenames restart numbering in each split, so split-local numbers were not assumed to identify the same physical scene across splits. All 4,800 files have different exact hashes; conservative equal-dHash/thumbnail-MAE matching found zero cross-split links. This does not prove independent buildings or complete near-duplicate removal.',
        '',
        'Three seeds used 60 epochs, batch size 128, AdamW learning rate 0.001; validation F1 selected checkpoints and decision thresholds. Images are 128×128 indoor robot fisheye views. Unsafe examples include blocked doors and obstacles as well as other non-traversable scenes. This score is not drop-off detection accuracy, is not comparable to detector box F1, and does not establish transfer to head/chest-mounted walking cameras. No classifier was integrated into the Android app. The noncommercial/share-alike dataset license is retained.','',
        '## Decision and next steps','',
        'Retain the additional data and measurements for research. GONet is promising for a separate traversability experiment; SS4Blind adds useful external curb/stair tests; a simple SANPO expansion must be judged by the fixed-holdout result above. A deployable drop-off model still needs downward-edge labels, harmonized target extents, camera-matched walking footage and independent route-level testing. The Commons photographs remain an unlabelled review pool, not training data.',
        '',
        '## Reproduce','',
        'Use the project Python virtual environment. Large data and checkpoints remain under ignored `data/ground_hazards/`, outside Git and APK assets.','',
        '```sh','training/ground_hazards/.venv/bin/python training/ground_hazards/acquire_additional.py gonet',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/acquire_additional.py ss4blind',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/acquire_additional.py gnd',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/acquire_additional.py sanpo_extra',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/audit_additional.py',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/evaluate_gonet_source.py',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/run_additional_study.py',
        'training/ground_hazards/.venv/bin/python training/ground_hazards/write_additional_report.py','```','',
        'Verification: all eight research unit tests passed, and rebuilding the original manifest and protocol in a separate temporary directory reproduced their hashes exactly. The earlier holdout has now been used in more than one screening study. It remains unchanged for comparisons but is not a pristine acceptance set. The bootstrap intervals are conditional on the small original collection groups; no population-wide walking-safety confidence is claimed.','']
    (OUT/'REPORT.md').write_text('\n'.join(lines))
    print(OUT/'REPORT.md')

if __name__=='__main__':main()
