"""Splits the key frame of the library screen into layers (one PNG per poster, label, nav pill, chip, rail letter).

What:   The 3D opening and ending of the promo film (assemble.py, moves WALL2LIB, WALL2LIB_LONG, LIB2LOGO) build the
        screen out of its parts. This script cuts them out of one still frame of the real app: it finds every element
        from the uiautomator dump of that frame, solves each element's alpha and colour exactly, rebuilds what is
        behind it (base.png) and writes layers/*.png (straight RGBA, alpha-over in z order) and layout.json.
        The layers over base.png reproduce the key frame (PSNR and error counts are printed and saved in layout.json).
How:    python scripts/media/promo/layers/build_lib.py [OUT_DIR]
        OUT_DIR (default build/promo/layers/LIB, or env LAYER_OUT) holds keyframe.png (the first frame of the library
        take, pts 0) and lib.xml (the uiautomator dump of the screen at that moment, see drive_lang.py). Env A11Y
        overrides the dump's path, env TAKE names the take for layout.json.
        Per language: OUT_DIR = build/promo/layers/LIB_de and LIB_es, each from that language's take.
Output: OUT_DIR/base.png, layers/*.png, layout.json, recomposed.png, diff_x16.png, boxes_overlay.png
Needs:  numpy, opencv-python.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("LAYER_OUT", ROOT / "build" / "promo" / "layers" / "LIB"))
KEY = OUT / "keyframe.png"
os.environ.setdefault("KEYFRAME", str(KEY))
os.environ.setdefault("A11Y", str(OUT / "lib.xml"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
Kbgr=cv2.imread(str(KEY));K=Kbgr[:,:,::-1].astype(np.float64);H,W=K.shape[:2]
import bg
B=bg.fit(7)[0]                       # smooth background model
rng=np.random.default_rng(7);NOISE=rng.normal(0,0.85,(H,W,3))
BFILL=np.clip(np.rint(B+NOISE),0,255)

# ------------------------------------------------------------------ geometry
def cov(box,r,x0,y0,x1,y1,ss=8):
    """Anti-aliased coverage of a rounded rectangle over the pixel window [x0,x1)x[y0,y1)."""
    bx0,by0,bx1,by1=box
    w=x1-x0;h=y1-y0
    xs=(np.arange(w*ss)+0.5)/ss+x0;ys=(np.arange(h*ss)+0.5)/ss+y0
    X,Y=np.meshgrid(xs,ys)
    cx=(bx0+bx1)/2;cy=(by0+by1)/2;hw=(bx1-bx0)/2-r;hh=(by1-by0)/2-r
    dx=np.maximum(np.abs(X-cx)-hw,0);dy=np.maximum(np.abs(Y-cy)-hh,0)
    ins=(dx*dx+dy*dy<=r*r)&(np.abs(X-cx)<=(bx1-bx0)/2)&(np.abs(Y-cy)<=(by1-by0)/2)
    return ins.reshape(h,ss,w,ss).mean((1,3))

def full_cov(box,r):
    x0=max(int(np.floor(box[0]))-1,0);y0=max(int(np.floor(box[1]))-1,0)
    x1=min(int(np.ceil(box[2]))+1,W);y1=min(int(np.ceil(box[3]))+1,H)
    a=np.zeros((H,W));a[y0:y1,x0:x1]=cov(box,r,x0,y0,x1,y1);return a


# ------------------------------------------------------------------ elements
def a11y():
    out=[]
    for n in ET.parse(os.environ["A11Y"]).getroot().iter("node"):
        b=[int(v) for v in re.findall(r"\d+",n.get("bounds"))]
        out.append(dict(cls=n.get("class").split(".")[-1],b=b,t=n.get("text") or n.get("content-desc") or ""))
    return out
N=a11y()
def text_nodes(pred): return [n for n in N if n["cls"]=="TextView" and pred(n)]

els=[]   # dicts
def add(**k): els.append(k)
# nav
_nv=[n for n in N if n["cls"]=="View" and n["b"][1]==35 and n["b"][3]==101 and n["b"][2]-n["b"][0]>600][0]["b"]
NAVC=(_nv[0]-7,34,_nv[2]+7,102)
add(id="nav_container",kind="nav",type="shape",box=NAVC,r=34,level=0,z=5,group="nav",shadow=(20,10,20,55))
_R=chr(0xfffd)
FIX={"Pel"+_R+"culas":"Pel\u00edculas","105 t"+_R+"tulos":"105 t\u00edtulos","G"+_R+"neros":"G\u00e9neros","T"+_R+"tulo":"T\u00edtulo","M"+_R+"sica":"M\u00fasica"}
fx=lambda t:FIX.get(t,t)
items=sorted([n for n in N if n["cls"]=="View" and n["b"][1]==41 and n["b"][3]==95 and n["b"][2]-n["b"][0]>60 and n["b"][0]>=_nv[0]+56 and n["b"][2]<1289],key=lambda n:n["b"][0])
names=["home","movie","show","music","photos","livetv","favorites"]
tn=text_nodes(lambda n:n["b"][1]<100)
assert len(items)==7,[i["b"] for i in items]
for nm,it in zip(names,items):
    t=[x for x in tn if x["b"][0]>=it["b"][0] and x["b"][2]<=it["b"][2]][0]
    if nm=="movie":
        add(id="nav_movie_active",kind="nav",type="shape",box=tuple(it["b"]),r=27,ringk=12,level=1,z=20,group="nav",parent="nav_container",title=fx(t["t"]))
    else:
        add(id=f"nav_{nm}",kind="nav",type="glyph",box=tuple(t["b"]),pad=4,level=1,z=20,group="nav",parent="nav_container",title=fx(t["t"]))
sb=sorted([n for n in N if n["cls"]=="View" and n["b"][1]==57 and n["b"][3]==79 and n["b"][2]-n["b"][0]==22],key=lambda n:n["b"][0])
add(id="nav_search",kind="nav",type="glyph",box=tuple(sb[0]["b"]),pad=5,level=1,z=20,group="nav",parent="nav_container")
add(id="nav_settings",kind="nav",type="glyph",box=tuple(sb[-1]["b"]),pad=5,level=1,z=20,group="nav",parent="nav_container")
add(id="nav_avatar",kind="avatar",type="shape",box=(1364,50,1400,86),r=18,level=1,z=20,group="nav",parent="nav_container")
hd=sorted(text_nodes(lambda n:n["b"][0]==80 and 150<=n["b"][1]<=225),key=lambda n:n["b"][1])
add(id="heading_movies",kind="text",type="glyph",box=tuple(hd[0]["b"]),pad=4,level=0,z=20,group="header",title=fx(hd[0]["t"]))
add(id="heading_count",kind="text",type="glyph",box=tuple(hd[1]["b"]),pad=4,level=0,z=20,group="header",title=fx(hd[1]["t"]))
ch=sorted([n for n in N if n["cls"]=="View" and n["b"][1]==281 and n["b"][3]==333],key=lambda n:n["b"][0])
assert len(ch)==6
for nm,n in zip(["all","collections","genres","unwatched","favorites","sort"],ch):
    inside=[x for x in text_nodes(lambda t:True) if x["b"][0]>=n["b"][0] and x["b"][2]<=n["b"][2] and x["b"][1]>=281 and x["b"][3]<=333]
    add(id=f"chip_{nm}",kind="chip",type="shape",box=tuple(n["b"]),r=26,level=0,z=15,group="chips",title=fx(inside[0]["t"]) if inside else "")
# posters
tit=text_nodes(lambda n:700<=n["b"][1]<=750 and n["b"][3]<=775)
for row,y0 in enumerate([371,794]):
    for c in range(7):
        x0=80+242*c if row==1 else (322+242*(c-1) if c>0 else 72)
        if row==0 and c==0: box=(68.0,353.5,308.0,708.4);r=27
        else: box=(x0,y0,x0+216,y0+324);r=22
        pid=f"poster_r{row}c{c}"
        add(id=pid,kind="poster",type="shape",box=box,r=r,level=0,z=10,group=f"row{row}",row=row,col=c)
        if row==0:
            xs=[n for n in tit if abs(n["b"][0]-(72 if c==0 else x0))<4]
            xs.sort(key=lambda n:n["b"][1])
            add(id=f"title_r0c{c}",kind="text",type="glyph",box=tuple(xs[0]["b"]),pad=4,level=0,z=20,group="row0",title=xs[0]["t"],row=0,col=c,of=pid)
            add(id=f"year_r0c{c}",kind="text",type="glyph",box=tuple(xs[1]["b"]),pad=4,level=0,z=20,group="row0",title=xs[1]["t"],row=0,col=c,of=pid)
for e in els:
    if e["kind"]=="poster" and e["row"]==0: e["title"]=next(x["title"] for x in els if x["id"]==f"title_r0c{e['col']}")
# rail
RAILB=(1850,371,1898,947)
add(id="rail_container",kind="rail",type="shape",box=RAILB,r=24,level=0,z=5,group="rail")
add(id="rail_hash",kind="rail",type="shape",box=(1857,389,1891,409),r=10,ringk=6,level=1,z=20,group="rail",parent="rail_container",title="#")
for i,ch in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZ"):
    y=409+20*i
    add(id=f"rail_{ch}",kind="rail",type="glyph",box=(1859,y+1,1889,y+19),pad=0,level=1,z=20,group="rail",parent="rail_container",title=ch)

# ------------------------------------------------------------------ container fills
def polyfit_region(mask,feat_fn):
    ys,xs=np.where(mask);A=feat_fn(xs.astype(float),ys.astype(float))
    return [np.linalg.lstsq(A,K[ys,xs,c],rcond=None)[0] for c in range(3)],feat_fn
def poly_field(coefs,feat_fn,x0,y0,x1,y1):
    Y,X=np.mgrid[y0:y1,x0:x1];A=feat_fn(X.ravel().astype(float),Y.ravel().astype(float))
    return np.stack([(A@coefs[c]).reshape(y1-y0,x1-x0) for c in range(3)],-1)
def fx_nav(x,y):
    xs=(x-NAVC[0])/(NAVC[2]-NAVC[0])*2-1;ys=(y-34)/68*2-1
    return np.stack([xs**i*ys**j for i in range(9) for j in range(3)],-1)
def fx_rail(x,y):
    ys=(y-371)/576*2-1;xs=(x-1850)/48*2-1
    return np.stack([ys**j*xs**i for j in range(3) for i in range(2)],-1)
def inflate(b,p): return (b[0]-p,b[1]-p,b[2]+p,b[3]+p)
def inbox(m,b,v=True):
    x0,y0,x1,y1=[int(round(t)) for t in b];m[max(y0,0):y1,max(x0,0):x1]=v
UF=np.zeros((H,W,3))   # container fill fields (level 1 Under)
CF={}
for cid,box,r,feat,inset,strip in [("nav_container",NAVC,34,fx_nav,4,None),("rail_container",RAILB,24,fx_rail,3,(1857,1891))]:
    m=full_cov((box[0]+inset,box[1]+inset,box[2]-inset,box[3]-inset),r-inset)>0.999
    for e in els:
        if e.get("parent")==cid and not strip: inbox(m,inflate(e["box"],6),False)
    if strip: 
        mm=np.zeros_like(m);mm[:,strip[0]-1:strip[1]+1]=True;m&=~mm
    coefs,_=polyfit_region(m,feat)
    x0,y0,x1,y1=[int(v) for v in box]
    fld=poly_field(coefs,feat,x0,y0,x1,y1);UF[y0:y1,x0:x1]=fld
    resid=(K[m]-UF[m]);print(cid,"fill fit residual std",resid.std(0).round(2),"samples",m.sum())

# ------------------------------------------------------------------ alphas
def win_of(e,extra=0):
    b=e["box"];p=e.get("pad",3)+extra
    return (max(int(np.floor(b[0]))-p,0),max(int(np.floor(b[1]))-p,0),min(int(np.ceil(b[2]))+p,W),min(int(np.ceil(b[3]))+p,H))
def under_for(e):
    return UF if e["level"]==1 else B
def alpha_of(e):
    U=under_for(e)
    if e["type"]=="shape":
        sh=e.get("shadow");m=sh or (0,0,0,0)
        x0=max(int(np.floor(e["box"][0]))-3-m[0],0);y0=max(int(np.floor(e["box"][1]))-3-m[1],0)
        x1=min(int(np.ceil(e["box"][2]))+3+m[2],W);y1=min(int(np.ceil(e["box"][3]))+3+m[3],H)
        a=cov(e["box"],e["r"],x0,y0,x1,y1)
        Kw=K[y0:y1,x0:x1];Uw=U[y0:y1,x0:x1]
        diff=np.abs(Kw-Uw).max(2)
        a=np.where((a<0.5)&(diff<2.5),0,a)
        if e["kind"] in("poster",) :
            # the artwork bleeds a fraction of a pixel past the layout bounds: pick it up from the image itself
            bx=e["box"];ring=cv2.dilate((a>0).astype(np.uint8),np.ones((5,5),np.uint8))>0
            ring&=(a<0.5)
            yy,xx=np.mgrid[y0:y1,x0:x1]
            qx=np.clip(xx,int(bx[0])+4,int(bx[2])-5);qy=np.clip(yy,int(bx[1])+4,int(bx[3])-5)
            qy=np.minimum(qy,H-1)
            P=K[qy,qx];V=P-Uw;vv=(V*V).sum(2);ar=np.clip(((Kw-Uw)*V).sum(2)/np.maximum(vv,1),0,1)
            ar=np.where((vv>900)&(ar>0.06),ar,0)
            a=np.where(ring,np.maximum(a,ar),a)
        if sh:
            dm=Uw.mean(2)-Kw.mean(2);s=np.where((a<1)&(dm>=1.5),np.clip(dm/np.maximum(Uw.mean(2),1),0,0.6),0)
            # shadow only below/around, never over a drawn element of the same layer
            a=a+(1-a)*s
        return (x0,y0,x1,y1),a
    x0,y0,x1,y1=win_of(e);Kw=K[y0:y1,x0:x1];Uw=U[y0:y1,x0:x1];D=Kw-Uw
    n=np.sqrt((D*D).sum(2));k=max(4,int(0.02*n.size));idx=np.argsort(n.ravel())[-k:]
    Fg=Kw.reshape(-1,3)[idx].mean(0);V=Fg-Uw;vv=(V*V).sum(2)
    a=np.clip((D*V).sum(2)/np.maximum(vv,1),0,1)
    thr=np.maximum(0.035,3.0/np.sqrt(np.maximum(vv,1)))
    a=np.where(a<thr,0,a);e["fg"]=[int(v) for v in Fg.round()]
    return (x0,y0,x1,y1),a
A={}
for e in els:
    win,a=alpha_of(e);a8=np.rint(a*255)/255;A[e["id"]]=(win,a8)
# ------------------------------------------------------------------ plates
def smooth_resid(R,M,sig=(5,16,48,140)):
    """Normalised convolution of the residual R over the valid pixels M, finest scale that has support."""
    Rf=R.astype(np.float32);Mf=M.astype(np.float32);out=np.zeros_like(Rf);done=np.zeros(M.shape,bool)
    for s in sig:
        num=np.stack([cv2.GaussianBlur(Rf[...,c]*Mf,(0,0),s) for c in range(3)],-1);den=cv2.GaussianBlur(Mf,(0,0),s)
        ok=(den>0.2)&~done;out[ok]=(num/np.maximum(den[...,None],1e-6))[ok];done|=ok
    return out
PARENT_IN={"nav_container":full_cov((NAVC[0]+1,35,NAVC[2]-1,101),33),"rail_container":full_cov((1851,372,1897,946),23)}
def hole_of(e,a):
    h=(a>0).astype(np.uint8);k=e.get("ringk",2 if e["type"]=="shape" else 1)
    d=cv2.dilate(h,np.ones((2*k+1,2*k+1),np.uint8))>0
    if e.get("parent"):
        x0,y0,x1,y1=A[e["id"]][0]
        d&=PARENT_IN[e["parent"]][y0:y1,x0:x1]>0.99
    return d
H0=np.zeros((H,W),bool);H1=np.zeros((H,W),bool)
for e in els:
    (x0,y0,x1,y1),a=A[e["id"]]
    tgt=H0 if e["level"]==0 else H1
    tgt[y0:y1,x0:x1]|=hole_of(e,a)
H0|=H1                                   # (container areas are inside level-0 holes anyway)
# level-0 fill: background model + local residual
Rs0=smooth_resid(K-B,~H0)
BF=np.clip(np.rint(B+Rs0+NOISE),0,255)
Base=K.copy();Base[H0]=BF[H0];Base=np.rint(Base)
# level-1 fill inside the containers: container fill model + local residual
Kp=K.copy()
cont=np.zeros((H,W),bool)
for cid,box in (("nav_container",NAVC),("rail_container",RAILB)):
    x0,y0,x1,y1=[int(v) for v in box];cont[y0:y1,x0:x1]=True
M1=cont&~H1
def laplace_fill(R,valid,box,iters=4000):
    """Harmonic (diffusion) fill of the residual R inside the holes of box from the valid pixels around them."""
    x0,y0,x1,y1=[int(v) for v in box];Rc=R[y0:y1,x0:x1].astype(np.float32).copy();vc=valid[y0:y1,x0:x1]
    init=smooth_resid(R,valid,sig=(24,80))[y0:y1,x0:x1]
    Rc=np.where(vc[...,None],Rc,init)
    for _ in range(iters):
        sm=cv2.blur(Rc,(3,3))
        Rc=np.where(vc[...,None],Rc,sm)
    out=np.zeros(R.shape,np.float32);out[y0:y1,x0:x1]=Rc;return out
Rs1=np.zeros((H,W,3),np.float32)
for cid,box in (("nav_container",NAVC),("rail_container",RAILB)):
    Rs1+=laplace_fill(K-UF,M1|~cont,box)*np.float32(1)
Rs1=np.where(cont[...,None],Rs1,0)
F1=np.clip(np.rint(UF+Rs1+NOISE),0,255)
Kp[H1]=F1[H1]
# residual halo (compression ringing, soft text edges) next to an element travels with it as a faint, exact layer
ACORE={k:v for k,v in A.items()}
for e in els:
    (x0,y0,x1,y1),a=A[e["id"]]
    hole=hole_of(e,a)
    if e.get("shadow"):
        shc=cov(e["box"],e["r"],x0,y0,x1,y1);a=np.where(shc==0,0,a)   # shadow pixels are re-solved exactly below
    ring=hole&(a==0)
    if not ring.any(): continue
    U=(Base if e["level"]==0 else Kp)[y0:y1,x0:x1];D=K[y0:y1,x0:x1]-U
    need=np.where(D>0,D/np.maximum(255-U,1),-D/np.maximum(U,1)).max(2)
    ar=np.where(np.abs(D).max(2)>=1.5,np.maximum(need,3/255),0)
    ar=np.minimum(np.ceil(ar*255+1e-6)/255,1.0)*ring
    A[e["id"]]=((x0,y0,x1,y1),a+ar)
    e['shadow_solved']=True
# ------------------------------------------------------------------ layers
LD=OUT/"layers";LD.mkdir(exist_ok=True)
for f in LD.glob("*.png"): f.unlink()
def make_layer(e):
    (x0,y0,x1,y1),a=A[e["id"]]
    ys,xs=np.where(a>0)
    cx0=max(xs.min()+x0-4,0);cy0=max(ys.min()+y0-4,0);cx1=min(xs.max()+1+x0+4,W);cy1=min(ys.max()+1+y0+4,H)
    full=np.zeros((H,W));full[y0:y1,x0:x1]=a
    ac=full[cy0:cy1,cx0:cx1]
    under=(Base if e["level"]==0 else Kp)[cy0:cy1,cx0:cx1]
    if e["level"]==0: Kt=Kp[cy0:cy1,cx0:cx1]
    else: Kt=K[cy0:cy1,cx0:cx1]
    Dd=Kt-under;need=np.where(Dd>0,Dd/np.maximum(255-under,1),-Dd/np.maximum(under,1)).max(2)
    ac=np.where(ac>0,np.minimum(np.maximum(ac,np.ceil(need*255-1e-6)/255),1.0),0)   # keep every colour inside 0..255
    C=(Kt-(1-ac[...,None])*under)/np.maximum(ac[...,None],1e-6)
    C=np.where(ac[...,None]>0,C,0)
    if e.get("shadow"):  # shadow pixels outside the shape are black
        sh=np.zeros((H,W));sh[y0:y1,x0:x1]=cov(e["box"],e["r"],x0,y0,x1,y1);shc=sh[cy0:cy1,cx0:cx1]
        core=np.zeros((H,W));core[y0:y1,x0:x1]=ACORE[e["id"]][1];coc=core[cy0:cy1,cx0:cx1]
        pass
    C=np.clip(np.rint(C),0,255).astype(np.uint8)
    a8=np.rint(ac*255).astype(np.uint8)
    rgb=C.copy();hole=(a8==0)
    if hole.any() and (~hole).any(): rgb=cv2.inpaint(rgb,hole.astype(np.uint8)*255,3,cv2.INPAINT_TELEA)
    png=np.dstack([rgb,a8])
    name=f"{e['id']}.png";cv2.imwrite(str(LD/name),cv2.cvtColor(png,cv2.COLOR_RGBA2BGRA))
    e.update(png=f"layers/{name}",x=int(cx0),y=int(cy0),w=int(cx1-cx0),h=int(cy1-cy0),rgb=rgb,a8=a8)
order=sorted(els,key=lambda e:(e["level"],e["z"]))
# containers (z 5) first within each level 0, then others
for e in order: make_layer(e)
cv2.imwrite(str(OUT/"base.png"),Base[:,:,::-1].astype(np.uint8))
# ------------------------------------------------------------------ verification
out=Base.copy()
for e in order:
    x,y,w,h=e["x"],e["y"],e["w"],e["h"];a=(e["a8"]/255.0)[...,None];C=e["rgb"].astype(np.float64)
    out[y:y+h,x:x+w]=a*C+(1-a)*out[y:y+h,x:x+w]
rec=np.clip(np.rint(out),0,255).astype(np.uint8)
diff=np.abs(rec.astype(int)-K.astype(int))
mse=(diff.astype(float)**2).mean();psnr=10*np.log10(255**2/mse) if mse>0 else 999
stats=dict(psnr=round(float(psnr),2),max_abs=int(diff.max()),mean_abs=round(float(diff.mean()),4),
           px_gt1=int((diff.max(2)>1).sum()),px_gt2=int((diff.max(2)>2).sum()),px_gt4=int((diff.max(2)>4).sum()),px_gt8=int((diff.max(2)>8).sum()),
           total_px=H*W)
print(stats)
cv2.imwrite(str(OUT/"recomposed.png"),rec[:,:,::-1])
cv2.imwrite(str(OUT/"diff_x16.png"),np.clip(diff*16,0,255).astype(np.uint8)[:,:,::-1])
# overlay
ov=Kbgr.copy()
for e in els:
    col=dict(nav=(0,255,255),chip=(255,0,255),poster=(0,255,0),text=(0,128,255),rail=(255,255,0),avatar=(255,0,0))[e["kind"]]
    b=e["box"];cv2.rectangle(ov,(int(b[0]),int(b[1])),(int(b[2]),int(b[3])),col,1)
    cv2.rectangle(ov,(e["x"],e["y"]),(e["x"]+e["w"],e["y"]+e["h"]),(255,255,255) if e["w"]*e["h"]<20000 else col,1)
cv2.imwrite(str(OUT/"boxes_overlay.png"),ov)
lay=[]
for zi,e in enumerate(order):
    d=dict(id=e["id"],kind=e["kind"],png=e["png"],x=e["x"],y=e["y"],w=e["w"],h=e["h"],z_order=zi+1,group=e["group"],
           box=[round(float(v),2) for v in e["box"]])
    for k in("title","parent","of","row","col","fg"):
        if k in e: d[k]=e[k]
    if e["type"]=="shape": d["corner_radius"]=e["r"]
    if e["y"]+e["h"]>=H or e["x"]+e["w"]>=W: d["clipped_at_screen_edge"]=True
    lay.append(d)
json.dump(dict(keyframe="keyframe.png",base="base.png",take=os.environ.get("TAKE","library.mp4"),take_timestamp_s=0.0,take_frame_index=0,
               canvas=[W,H],blend="straight (non-premultiplied) RGBA, normal alpha-over, in z_order over base.png",layers=lay,verification=stats),
          open(OUT/"layout.json","w"),indent=1)
from collections import Counter
print(Counter(e["kind"] for e in els),len(els))
