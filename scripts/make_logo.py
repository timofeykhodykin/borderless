#!/usr/bin/env python3
"""Draws docs/logo.svg and docs/logo-dark.svg: the heron of the launcher icon, then "Border" in PT Serif Bold and
"(less)" in PT Serif Italic, the text turned into outlines (renders the same everywhere, no web fonts).

Needs fontTools: python3 -m venv /tmp/v && /tmp/v/bin/pip install fonttools && /tmp/v/bin/python scripts/make_logo.py
"""
import os
import re
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
root=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))+'/'
d=re.search(r'pathData="([^"]+)"',open(root+'app/src/main/res/drawable/ic_launcher_foreground.xml').read()).group(1)
x0,x1,y0,y1=-63.0,15380.0,0.0,9896.0  # bounds of the heron path (potrace units)
H=112.0                     # heron height
k=H/(y1-y0); W=k*(x1-x0)
def text_path(font_file, text, size, x, base):
    f=TTFont(font_file); gs=f.getGlyphSet(); cmap=f.getBestCmap(); upm=f['head'].unitsPerEm; hmtx=f['hmtx']
    sc=size/upm; out=[]
    for ch in text:
        g=cmap[ord(ch)]
        pen=SVGPathPen(gs)
        gs[g].draw(TransformPen(pen,(sc,0,0,-sc,x,base)))
        out.append(pen.getCommands()); x+=hmtx[g][0]*sc
    return ' '.join(out), x
size=104; gap=30
tx=W+gap; base=H*0.80
bold,xe=text_path(root+'app/src/main/res/font/pt_serif_bold.ttf','Border',size,tx,base)
ital,xe=text_path(root+'app/src/main/res/font/pt_serif_italic.ttf','(less)',size,xe+2,base)
total=xe+6
for name,ink in (('logo.svg','#2B2422'),('logo-dark.svg','#F3EDEA')):
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -8 {total:.0f} {H+30:.0f}" width="{total/1.6:.0f}" height="{(H+30)/1.6:.0f}" role="img" aria-label="Border(less)">
<g transform="translate({k*x1:.3f},{k*y1:.3f}) scale({-k:.6f},{-k:.6f})"><path fill="#E0654D" d="{d}"/></g>
<path fill="{ink}" d="{bold}"/>
<path fill="{ink}" d="{ital}"/>
</svg>
'''
    open(root+'docs/'+name,'w').write(svg)
print(total, H)
