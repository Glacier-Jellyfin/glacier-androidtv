"""Background model of a screen: a smooth polynomial surface fitted to the key frame outside every UI element.

build_lib.py uses it to rebuild what is behind each element when it lifts off the screen. Reads the key frame
(env KEYFRAME) and the uiautomator dump of the screen (env A11Y); build_lib.py sets both. Run on its own it
prints the fit residual for polynomial degrees 4, 6 and 8:  KEYFRAME=keyframe.png A11Y=lib.xml python bg.py
"""
import os
import re
import xml.etree.ElementTree as ET

import cv2
import numpy as np

K = cv2.imread(os.environ["KEYFRAME"]).astype(np.float64)[:, :, ::-1]  # RGB
H,W=K.shape[:2]
def a11y():
    r=ET.parse(os.environ["A11Y"]).getroot();out=[]
    for n in r.iter("node"):
        b=[int(v) for v in re.findall(r"\d+",n.get("bounds"))]
        out.append(dict(cls=n.get("class").split(".")[-1],b=b,t=n.get("text") or n.get("content-desc") or "",click=n.get("clickable")=="true"))
    return out
N=a11y()
boxes=[]
for n in N:
    x0,y0,x1,y1=n["b"]
    if x1-x0>=1900 or (x1-x0)>1000 and y1-y0>900: continue
    if n["cls"]=="View" and n["click"] and (x1-x0)<250 and (y1-y0)>200: boxes.append((x0-12,y0-12,x1+12,y1+12))
    elif n["cls"]=="TextView" or (n["click"] and y1<340): boxes.append((x0-8,y0-8,x1+8,y1+8))
_nv=[n for n in N if n['cls']=='View' and not n['click'] and n['b'][1]==35 and n['b'][3]==101 and n['b'][2]-n['b'][0]>600][0]['b']
boxes+= [(_nv[0]-47,20,1460,150),(1835,360,1910,960)]
excl=np.zeros((H,W),bool)
for x0,y0,x1,y1 in boxes: excl[max(y0,0):y1,max(x0,0):x1]=True
def feats(xs,ys,deg):
    x=xs/W*2-1;y=ys/H*2-1;cols=[]
    for i in range(deg+1):
        for j in range(deg+1-i): cols.append((x**i)*(y**j))
    return np.stack(cols,-1)
def fit(deg=6,extra=None):
    m=~excl if extra is None else (~excl)|extra
    ys,xs=np.where(m);sel=np.arange(0,len(ys),3);ys,xs=ys[sel],xs[sel]
    A=feats(xs.astype(float),ys.astype(float),deg)
    coef=[np.linalg.lstsq(A,K[ys,xs,c],rcond=None)[0] for c in range(3)]
    Y,X=np.mgrid[0:H,0:W]
    F=feats(X.ravel().astype(float),Y.ravel().astype(float),deg)
    B=np.stack([(F@coef[c]).reshape(H,W) for c in range(3)],-1)
    return B,ys,xs
if __name__=="__main__":
    for deg in (4,6,8):
        B,ys,xs=fit(deg);r=K[ys,xs]-B[ys,xs]
        print(deg,np.abs(r).mean(),r.std(0),np.abs(r).max())
