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
    if manifest.get('mode') not in ['MAINTENANCE','CATALOG_ONLY']:
        raise ValueError('Only maintenance or catalog-only artifacts are permitted')
    if manifest.get('liveOrdering') is not False or manifest.get('customerMutations') is not False or manifest.get('functions') is not False:
        raise ValueError('Only the reviewed static, disabled-ordering package is permitted')
    return url

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--approval',required=True,type=Path)
    parser.add_argument('--artifact',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    manifest_bytes=(args.artifact/'deployment-manifest.json').read_bytes()
    approval=json.loads(args.approval.read_text(encoding='utf-8'))
    url=approved_target(approval,manifest_bytes)
    class SameOrigin(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,headers,newurl):
            if urllib.parse.urlsplit(newurl)[:2]!=urllib.parse.urlsplit(url)[:2]:
                raise ValueError('HTTPS verification redirected outside the approved origin')
            return super().redirect_request(req,fp,code,msg,headers,newurl)
    opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context()),SameOrigin())
    def get(route):
        with opener.open(url+route,timeout=15) as response:
            data=response.read(2_000_001)
            if len(data)>2_000_000:raise ValueError('Unexpected oversized verification response')
            return data,response.headers
    remote,_=get('deployment-manifest.json')
    if remote!=manifest_bytes:raise ValueError('Deployed artifact differs from the owner-reviewed package')
    page,headers=get('')
    if b'data-mode="catalog-only"' not in page:raise ValueError('Unknown deployed customer page')
    policy=headers.get('Content-Security-Policy','')
    if "form-action 'none'" not in policy or "frame-ancestors 'none'" not in policy:
        raise ValueError('Deployed security headers have not been verified')
    settings,_=get('public-config.json');settings=json.loads(settings)
    if settings.get('liveOrdering') is not False or settings.get('mode')!=manifest.get('mode'):
        raise ValueError('Deployed page settings do not match the reviewed static artifact')
    for route in ['api/orders','api/recover','api/orders/00000000-0000-4000-a000-000000000000/edit']:
        try:get(route)
        except urllib.error.HTTPError as error:
            if error.code not in [404,405]:raise
        else:raise ValueError('Unexpected API/SPA fallback at '+route)
    # Actual media is authored only after every HTTPS/content gate above passes.
    from PIL import Image,ImageDraw
    from reportlab.graphics.barcode.qrencoder import QRCode,QRErrorCorrectLevel
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import mm
    code=QRCode(None,QRErrorCorrectLevel.M);code.addData(url);code.make()
    n=code.getModuleCount();quiet=4;scale=10
    image=Image.new('RGB',((n+quiet*2)*scale,)*2,'white');draw=ImageDraw.Draw(image)
    for row in range(n):
        for col in range(n):
            if code.isDark(row,col):
                x=(col+quiet)*scale;y=(row+quiet)*scale
                draw.rectangle((x,y,x+scale-1,y+scale-1),fill='black')
    args.out.mkdir(parents=True,exist_ok=False)
    png=args.out/'HardwareShopPOS-Catalog-QR.png';pdf=args.out/'HardwareShopPOS-Catalog-QR.pdf'
    image.save(png)
    c=canvas.Canvas(str(pdf),pagesize=A4);width,height=A4
    c.setTitle('HardwareShopPOS - Verified catalog QR')
    c.setFont('Helvetica-Bold',24);c.drawCentredString(width/2,height-50*mm,'HardwareShopPOS')
    c.setFont('Helvetica',14)
    subtitle='Scan to browse the shop catalog' if manifest['mode']=='CATALOG_ONLY' else 'Website preview - catalog being prepared'
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
      'httpsVerifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
      'deploymentId':approval['deploymentId'],'tlsCertificateVerification':True,
      'qrModules':n,'quietZoneModules':quiet,'files':{}}
    for file in [png,pdf]:result['files'][file.name]={'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'bytes':file.stat().st_size}
    (args.out/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
