"""After explicit account/project/content approval only: verify HTTPS, then PNG/PDF.

Never creates a QR for a localhost, placeholder, branch-preview or unverified URL.
This workflow record is not an authentication mechanism or proof of shop ownership.
Uses the existing ReportLab/Pillow runtime; no QR service or new paid dependency.
"""
import argparse, datetime, hashlib, json, re, ssl, urllib.error, urllib.parse, urllib.request
from pathlib import Path

def approved_target(approval, manifest_bytes):
    required=['accountApproved','projectApproved','contentApproved','deploymentApproved','freePlanVerified']
    if any(approval.get(k) is not True for k in required):
        raise ValueError('Explicit owner/account/project/content/deployment and Free-plan approval required')
    project=approval.get('pagesProject','')
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,56}[a-z0-9])?',project):
        raise ValueError('Use the approved permanent Pages project name')
    url=approval.get('websiteUrl','')
    if url!='https://'+project+'.pages.dev/':
        raise ValueError('Use the exact permanent HTTPS project.pages.dev URL')
    if approval.get('artifactManifestSha256')!=hashlib.sha256(manifest_bytes).hexdigest():
        raise ValueError('Reviewed artifact hash does not match approval')
    if not approval.get('deploymentId') or not approval.get('approvedAt'):
        raise ValueError('Record actual deployment ID and owner approval timestamp')
    manifest=json.loads(manifest_bytes)
    if manifest.get('mode') != 'CATALOG_ONLY' or not isinstance(manifest.get('productCount'),int) or manifest['productCount'] < 1:
        raise ValueError('Only approved, nonempty catalog-only artifacts are permitted')
    if manifest.get('liveOrdering') is not False or manifest.get('customerMutations') is not False or manifest.get('functions') is not False:
        raise ValueError('Only the reviewed static, disabled-ordering package is permitted')
    if approval.get('localAcceptancePassed') is not True and not (
        approval.get('localTestsDeferredByOwner') is True and approval.get('testDeferralScope') == 'STATIC_CATALOG_ONLY'
    ):
        raise ValueError('Local acceptance or explicit owner deferral for the static catalog is required')
    return url

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--approval',required=True,type=Path)
    parser.add_argument('--artifact',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--verification',type=Path,help='Fresh evidence from verify-published-catalog.mjs when the Python HTTPS client is unavailable')
    args=parser.parse_args()
    manifest_bytes=(args.artifact/'deployment-manifest.json').read_bytes()
    approval=json.loads(args.approval.read_text(encoding='utf-8'))
    url=approved_target(approval,manifest_bytes)
    class SameOrigin(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,headers,newurl):
            if urllib.parse.urlsplit(newurl)[:2]!=urllib.parse.urlsplit(url)[:2]:
                raise ValueError('HTTPS verification redirected outside the approved origin')
            return super().redirect_request(req,fp,code,msg,headers,newurl)
    opener=None
    def get(route):
        nonlocal opener
        if opener is None:
            opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context()),SameOrigin())
        with opener.open(url+route,timeout=15) as response:
            data=response.read(2_000_001)
            if len(data)>2_000_000:raise ValueError('Unexpected oversized verification response')
            return data,response.headers
    manifest=json.loads(manifest_bytes)
    allowed={'index.html','404.html','app.mjs','styles.css','catalog.json','public-config.json','_headers','robots.txt'}
    if set(manifest.get('files',{})) != allowed:
        raise ValueError('Unexpected artifact files')
    for name,entry in manifest['files'].items():
        local=(args.artifact/name).read_bytes()
        if len(local)!=entry.get('bytes') or hashlib.sha256(local).hexdigest()!=entry.get('sha256'):
            raise ValueError('Local artifact has changed: '+name)
    if manifest.get('approvedCatalogSha256')!=manifest['files']['catalog.json']['sha256']:
        raise ValueError('Catalog content approval does not match artifact')
    routes=['api/orders','api/recover','api/orders/00000000-0000-4000-a000-000000000000/edit']
    verification_time=datetime.datetime.now(datetime.timezone.utc).isoformat()
    verification_method='PYTHON_CERTIFICATE_VERIFIED_HTTPS'
    if args.verification:
        # This receipt is created by a real, separately executed HTTPS verifier,
        # not a mock or a claim that local/native acceptance tests passed.
        proof=json.loads(args.verification.read_text(encoding='utf-8'))
        if (proof.get('classification')!='ACTUAL_HTTPS_STATIC_PUBLICATION' or proof.get('schemaVersion')!=1
            or proof.get('url')!=url or proof.get('deploymentId')!=approval['deploymentId']
            or proof.get('tlsCertificateVerification') is not True or proof.get('securityHeadersVerified') is not True
            or proof.get('artifactManifestSha256')!=hashlib.sha256(manifest_bytes).hexdigest()
            or proof.get('catalogSha256')!=manifest['approvedCatalogSha256'] or proof.get('productCount')!=manifest['productCount']
            or proof.get('homeStatus')!=200 or proof.get('homeSha256')!=manifest['files']['index.html']['sha256']):
            raise ValueError('HTTPS verification evidence does not match the approved deployment')
        verified_at=datetime.datetime.fromisoformat(proof['verifiedAt'].replace('Z','+00:00'))
        if verified_at.tzinfo is None or abs((datetime.datetime.now(datetime.timezone.utc)-verified_at).total_seconds())>900:
            raise ValueError('HTTPS evidence must be timezone-aware and less than 15 minutes old')
        verified=proof.get('verifiedFiles',{})
        if set(verified)!=allowed-{'_headers'}:raise ValueError('Incomplete hosted-file verification')
        for name,record in verified.items():
            expected=manifest['files'][name]
            if (record.get('sha256')!=expected['sha256'] or record.get('bytes')!=expected['bytes']
                or record.get('status') not in ([200,404] if name=='404.html' else [200])
                or urllib.parse.urlsplit(record.get('finalUrl',''))[:2]!=urllib.parse.urlsplit(url)[:2]):
                raise ValueError('Invalid hosted-file evidence: '+name)
        statuses=proof.get('disabledApiStatuses',{})
        if set(statuses)!=set(routes) or any(value not in [404,405] for value in statuses.values()):
            raise ValueError('Disabled API routes were not verified')
        verification_time=proof['verifiedAt'];verification_method='NODE_CERTIFICATE_VERIFIED_HTTPS'
    else:
        remote,_=get('deployment-manifest.json')
        if remote!=manifest_bytes:raise ValueError('Deployed artifact differs from the owner-reviewed package')
        for name,entry in manifest['files'].items():
            if name=='_headers':continue  # Pages applies this control file as response headers.
            served,_=get(name)
            if len(served)!=entry['bytes'] or hashlib.sha256(served).hexdigest()!=entry['sha256']:
                raise ValueError('Hosted file differs from the reviewed artifact: '+name)
        page,headers=get('')
        if b'data-mode="catalog-only"' not in page:raise ValueError('Unknown deployed customer page')
        policy=headers.get('Content-Security-Policy','')
        if "form-action 'none'" not in policy or "frame-ancestors 'none'" not in policy:
            raise ValueError('Deployed security headers have not been verified')
        settings,_=get('public-config.json');settings=json.loads(settings)
        if settings.get('liveOrdering') is not False or settings.get('mode')!=manifest.get('mode'):
            raise ValueError('Deployed page settings do not match the reviewed static artifact')
        for route in routes:
            try:get(route)
            except urllib.error.HTTPError as error:
                if error.code not in [404,405]:raise
            else:raise ValueError('Unexpected API/SPA fallback at '+route)
    # Actual media is authored only after every HTTPS/content gate above passes.
    from PIL import Image,ImageDraw,ImageFont
    from reportlab.graphics.barcode.qrencoder import QRCode,QRErrorCorrectLevel
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import mm
    code=QRCode(None,QRErrorCorrectLevel.M);code.addData(url);code.make()
    n=code.getModuleCount();quiet=4;scale=max(20,(1400+n+7)//(n+8))
    side_pixels=(n+quiet*2)*scale
    image=Image.new('RGB',(side_pixels,side_pixels+150),'white');draw=ImageDraw.Draw(image)
    for row in range(n):
        for col in range(n):
            if code.isDark(row,col):
                x=(col+quiet)*scale;y=(row+quiet)*scale
                draw.rectangle((x,y,x+scale-1,y+scale-1),fill='black')
    label='SCAN TO VIEW ITEMS & PRICES'
    try:font=ImageFont.truetype('arial.ttf',40)
    except OSError:font=ImageFont.load_default()
    bounds=draw.textbbox((0,0),label,font=font)
    draw.text(((side_pixels-(bounds[2]-bounds[0]))/2,side_pixels+45),label,fill='black',font=font)
    args.out.mkdir(parents=True,exist_ok=False)
    png=args.out/'HardwareShopPOS-QR.png';pdf=args.out/'HardwareShopPOS-QR.pdf'
    image.save(png)
    c=canvas.Canvas(str(pdf),pagesize=A4);width,height=A4
    c.setTitle('HardwareShopPOS - Verified catalog QR')
    c.setFont('Helvetica-Bold',24);c.drawCentredString(width/2,height-50*mm,'HardwareShopPOS')
    c.setFont('Helvetica',14)
    subtitle='SCAN TO VIEW ITEMS & PRICES'
    c.drawCentredString(width/2,height-62*mm,subtitle)
    side=95*mm;unit=side/(n+quiet*2);left=(width-side)/2;bottom=height-175*mm
    c.setFillColorRGB(1,1,1);c.rect(left,bottom,side,side,fill=1,stroke=0);c.setFillColorRGB(0,0,0)
    for row in range(n):
        for col in range(n):
            if code.isDark(row,col):c.rect(left+(col+quiet)*unit,bottom+(n+quiet-row-1)*unit,unit,unit,fill=1,stroke=0)
    c.setFont('Helvetica',10);c.drawCentredString(width/2,bottom-12*mm,url)
    c.setFont('Helvetica-Bold',12)
    status='Catalog only - online ordering unavailable' if manifest['mode']=='CATALOG_ONLY' else 'Catalog and online ordering unavailable'
    c.drawCentredString(width/2,bottom-25*mm,status)
    c.setFont('Helvetica',10);c.drawCentredString(width/2,bottom-36*mm,'Please ask our team at the counter.');c.showPage();c.save()
    result={'url':url,'mode':manifest['mode'],'orderingEnabled':False,
      'httpsVerifiedAt':verification_time,'httpsVerificationMethod':verification_method,
      'deploymentId':approval['deploymentId'],'tlsCertificateVerification':True,
      'localAcceptance':'PASSED' if approval.get('localAcceptancePassed') is True else 'DEFERRED_BY_OWNER_STATIC_ONLY',
      'physicalPhoneScanVerified':False,
      'artifactManifestSha256':hashlib.sha256(manifest_bytes).hexdigest(),
      'catalogSha256':manifest['approvedCatalogSha256'],'label':label,
      'qrModules':n,'quietZoneModules':quiet,'files':{}}
    for file in [png,pdf]:result['files'][file.name]={'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'bytes':file.stat().st_size}
    (args.out/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
