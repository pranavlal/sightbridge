#!/usr/bin/env python3
"""Acquire a bounded openly licensed photo review pool; never infer labels from search."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import requests
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'data/ground_hazards/raw/commons_review'
QUERIES = ['stairs looking down', 'staircase viewed from above', 'sidewalk curb India',
           'open drain India', 'broken pavement India', 'pavement shadows']
ALLOWED = re.compile(r'^(CC BY(?:-SA)? [234]\.0|CC0|Public domain)$')
EXCLUDE = re.compile(r'nude|painting|drawing|diagram|portrait|artwork|engraving', re.I)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--per-query', type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.per_query <= 20:
        parser.error('--per-query must be between 1 and 20')
    OUT.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers['User-Agent'] = 'SightBridgeDatasetResearch/1.0 (https://github.com/pranavlal/sightbridge)'
    rows = []
    seen = set()
    failures = []
    for query in QUERIES:
        params = dict(action='query', generator='search', gsrsearch=query,
                      gsrnamespace=6, gsrlimit=20, prop='imageinfo',
                      iiprop='url|extmetadata|mime|sha1', iiurlwidth=1024, format='json')
        try:
            response = session.get('https://commons.wikimedia.org/w/api.php', params=params, timeout=45)
            response.raise_for_status()
            pages = response.json().get('query', {}).get('pages', {})
        except (requests.RequestException, ValueError) as error:
            failures.append({'query': query, 'error': str(error)})
            continue
        count = 0
        for page in sorted(pages.values(), key=lambda p: p.get('index', 0)):
            info = page.get('imageinfo', [{}])[0]
            meta = info.get('extmetadata', {})
            license_name = meta.get('LicenseShortName', {}).get('value', '')
            if not ALLOWED.fullmatch(license_name) or info.get('mime') != 'image/jpeg' or EXCLUDE.search(page['title']):
                continue
            if page['pageid'] in seen:
                continue
            source_url = info.get('thumburl', info.get('url'))
            path = OUT / f"{page['pageid']}.jpg"
            try:
                if not path.exists():
                    with session.get(source_url, timeout=45, stream=True) as image_response:
                        image_response.raise_for_status()
                        chunks, length = [], 0
                        for chunk in image_response.iter_content(65536):
                            length += len(chunk)
                            if length > 10 * 1024 * 1024:
                                raise ValueError('Image exceeds 10 MiB limit')
                            chunks.append(chunk)
                    part = path.with_suffix('.part')
                    part.write_bytes(b''.join(chunks))
                    with Image.open(part) as image:
                        image.verify()
                    part.replace(path)
                with Image.open(path) as image:
                    width, height = image.size
                sha = hashlib.sha256(path.read_bytes()).hexdigest()
                row = dict(page_id=page['pageid'], title=page['title'], query=query,
                           source_page=info.get('descriptionurl'), download_url=source_url,
                           original_url=info.get('url'), original_sha1=info.get('sha1'),
                           license=license_name, license_url=meta.get('LicenseUrl', {}).get('value'),
                           artist_html=meta.get('Artist', {}).get('value'),
                           credit_html=meta.get('Credit', {}).get('value'),
                           description_html=meta.get('ImageDescription', {}).get('value'),
                           metadata=meta, image=str(path.relative_to(ROOT)), width=width,
                           height=height, sha256=sha, review_status='unreviewed',
                           annotation_status='unlabelled', split='unassigned')
                rows.append(row)
                seen.add(page['pageid'])
                count += 1
                print(f"Downloaded {page['title']} [{license_name}]", flush=True)
            except (requests.RequestException, ValueError, OSError) as error:
                failures.append({'title': page['title'], 'error': str(error)})
            time.sleep(0.3)
            if count >= args.per_query:
                break
    (OUT / 'manifest.json').write_text(json.dumps(rows, indent=2) + '\n')
    (OUT / 'receipt.json').write_text(json.dumps(dict(images=len(rows), queries=QUERIES,
        failures=failures, purpose='manual review and annotation; excluded from training/test'), indent=2) + '\n')
    print(f'{len(rows)} verified images in review pool; {len(failures)} failures', flush=True)

if __name__ == '__main__':
    main()
