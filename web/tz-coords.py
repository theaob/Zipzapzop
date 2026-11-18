# Writes the time zone -> rough coordinates table (TZ_COORDS in page.html) from the system tzdata,
# so the sky buttons can place the sun without asking for location. Usage: python3 tz-coords.py > /tmp/tz.txt
import re
rows={}
for l in open('/usr/share/zoneinfo/zone.tab'):
    if l.startswith('#'): continue
    p=l.split('\t'); c=p[1]; z=p[2].strip()
    m=re.match(r'([+-]\d{2})(\d{2})(\d{2})?([+-]\d{3})(\d{2})(\d{2})?$',c)
    lat=int(m[1][1:])+int(m[2])/60; lat*= -1 if m[1][0]=='-' else 1
    lon=int(m[4][1:])+int(m[5])/60; lon*= -1 if m[4][0]=='-' else 1
    rows[z]=(round(lat),round(lon))
for l in open('/usr/share/zoneinfo/tzdata.zi'):
    if l.startswith('L '):
        _,t,a=l.split()
        if t in rows and a not in rows: rows[a]=rows[t]
# One string: "Area/City lat lon;..." with the area written once per group.
groups={}
for z,(a,b) in sorted(rows.items()):
    area,_,city=z.rpartition('/') if '/' in z else ('',None,z)
    groups.setdefault(area,[]).append(f'{city} {a} {b}')
s='|'.join(area+':'+','.join(v) for area,v in groups.items())
print(s)

