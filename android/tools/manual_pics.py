# Draws simple pictures of the app's own screens (same layout and colours as the APK), with gold numbered markers.
from PIL import Image, ImageDraw, ImageFont
import json, sys, os
OUT = sys.argv[1]
F = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"; FB = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
DJ="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
def f(s, b=False, t=""): return ImageFont.truetype(DJ if any(ord(ch) > 0x2000 for ch in t) else (FB if b else F), s)
BG=(14,13,11); CARD=(34,32,28); GOLD=(217,180,74); TXT=(240,236,226); SUB=(150,144,130); LINE=(60,56,48); RED=(229,72,59); GREEN=(61,220,151)
W,H=540,960
class S:
    def __init__(s): s.im=Image.new("RGB",(W,H),BG); s.d=ImageDraw.Draw(s.im); s.marks=[]
    def box(s,x,y,w,h,fill=CARD,r=16,outline=None,dash=False,wd=2):
        s.d.rounded_rectangle([x,y,x+w,y+h],r,fill=fill,outline=outline,width=wd)
    def btn(s,x,y,w,h,t,gold=False,sz=17,col=None,r=16):
        s.box(x,y,w,h,GOLD if gold else CARD,r)
        ft=f(sz,True,t); tw=s.d.textlength(t,font=ft); s.d.text((x+(w-tw)/2,y+(h-sz)/2-2),t,font=ft,fill=(20,18,14) if gold else (col or TXT))
    def txt(s,x,y,t,sz=16,col=TXT,b=False): s.d.text((x,y),t,font=f(sz,b,t),fill=col)
    def pic(s,x,y,w,h,c1=(70,60,45),c2=(30,40,48)):
        for i in range(h):
            k=i/max(1,h-1); s.d.line([(x,y+i),(x+w,y+i)],fill=tuple(int(c1[j]*(1-k)+c2[j]*k) for j in range(3)))
    def mark(s,x,y):
        n=len(s.marks)+1; s.marks.append(n); r=17
        s.d.ellipse([x-r,y-r,x+r,y+r],fill=GOLD,outline=(20,18,14),width=3); ft=f(18,True); tw=s.d.textlength(str(n),font=ft); s.d.text((x-tw/2,y-12),str(n),font=ft,fill=(20,18,14))
    def save(s,name): s.im.save(os.path.join(OUT,name),quality=88)

def home(L):
    s=S(); M=L=="ms"
    s.box(24,30,56,56,CARD,14); s.txt(37,42,"PM",20,GOLD,True)
    s.btn(206,30,56,56,"Chat",sz=12); s.btn(270,30,130,56,"Bahasa Melayu" if M else "English",sz=14); s.btn(408,30,108,56,"Admin",sz=15)
    s.btn(206,98,310,52,"Super Admin",sz=15,col=GOLD)
    s.txt(24,175,"MULTI-CAM STUDIO",14,GOLD,True); s.txt(24,195,"ProMultiCam",44,GOLD,True)
    s.txt(24,252,"Video studio dengan banyak telefon." if M else "Studio videos with many phones.",15,TXT)
    s.txt(24,290,"Nama peranti" if M else "Device name",14,SUB); s.box(24,312,492,48,(22,21,18),14,LINE)
    s.box(24,382,240,300,(30,27,20),20,GOLD,wd=3); s.box(276,382,240,300,(24,24,24),20,LINE)
    s.box(104,470,80,80,GOLD,20); s.box(356,470,80,80,(40,48,60),20)
    s.txt(62,580,"DIRECTOR",26,GOLD,True); s.txt(318,580,"CAMERA",26,TXT,True)
    labels=["Plans","Videos","Semak peranti" if M else "Device check","Template","App Update","About"]
    for i,l in enumerate(labels):
        x=24+(i%3)*167; y=708+(i//3)*110; s.btn(x,y,158,96,l,sz=15)
    s.mark(144,450); s.mark(396,450); s.mark(234,58); s.mark(102,808)
    return s
def director(L, settings=False):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,24,"ProMultiCam",24,TXT,True); s.txt(86,56,"1 kamera · ID 456239" if M else "1 camera · ID 456239",14,GREEN,True)
    for i,t in enumerate(["▶","▤","◐","☰"]): s.btn(300+i*58,26,52,56,t,sz=20)
    s.pic(60,100,420,590); s.btn(70,112,100,34,"Director",sz=13,col=GOLD); s.btn(178,112,140,34,"Supermassive",sz=13)
    s.txt(420,118,"30 fps",14,TXT,True); s.box(418,628,50,50,(20,20,20),25); s.txt(431,638,"⟳",24,TXT)
    s.box(24,710,80,110,(40,36,28),12,GOLD,wd=3); s.txt(34,792,"Director",12,TXT,True)
    s.box(116,710,80,110,(21,20,15),12,GOLD,wd=2); s.txt(146,742,"+",30,GOLD,True); s.txt(124,792,"Kamera" if M else "Camera",12,GOLD,True)
    s.btn(24,850,150,64,"|| " + ("Jeda" if M else "Pause"),sz=18); s.btn(186,850,330,64,"● " + ("Mula rakam" if M else "Start Recording"),gold=True,sz=19)
    if settings: return s
    s.mark(262,66); s.mark(156,700); s.mark(64,700); s.mark(443,610); s.mark(475,48); s.mark(417,48)
    return s
def join(L):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,38,"Kamera" if M else "Camera",24,TXT,True)
    s.pic(24,110,492,420,(50,60,70),(20,24,30))
    s.txt(24,560,"ID Studio" if M else "Studio ID",15,SUB); s.box(24,584,492,60,(22,21,18),14,LINE); s.txt(44,600,"456239",22,TXT,True)
    s.btn(24,664,240,60,"Imbas QR" if M else "Scan QR",sz=17); s.btn(276,664,240,60,"Sertai" if M else "Join",gold=True,sz=18)
    s.mark(40,600); s.mark(500,694); s.mark(250,694)
    return s
def record(L):
    s=director(L,True); M=L=="ms"
    s.box(70,160,150,36,(120,20,20),18); s.txt(84,166,"● REC 00:42",16,TXT,True)
    s.btn(24,850,150,64,"▶ " + ("Sambung" if M else "Resume"),sz=18); s.btn(186,850,330,64,"■ " + ("Tamat rakaman" if M else "Stop recording"),gold=True,sz=19)
    s.mark(100,840); s.mark(350,840); s.mark(145,150)
    return s
def sheet(s,title):
    s.box(0,300,W,H-300,(24,23,20),26); s.txt(24,326,title,26,TXT,True); s.btn(456,318,60,52,"X",sz=18)
def settings(L):
    s=director(L,True); M=L=="ms"; sheet(s,"Tetapan rakaman" if M else "Recording settings")
    s.txt(24,392,"Tukar sudut automatik" if M else "Auto switch angles",15,SUB,True); s.pic(24,418,492,80,(224,123,57),(90,42,16)); s.txt(220,446,"Cam 2",20,TXT,True)
    for i,t in enumerate(["Tutup" if M else "Off","5s","10s","15s"]): s.btn(24+i*124,508,116,44,t,gold=i==0,sz=15)
    s.txt(24,572,"Kesan tukar" if M else "Switch effect",15,SUB,True); s.pic(24,596,492,80,(45,143,138),(14,43,51))
    for i,t in enumerate(["Auto","Pudar" if M else "Fade","Gelap" if M else "Black","Gelongsor" if M else "Slide"]): s.btn(24+i*124,686,116,44,t,gold=i==0,sz=14)
    s.txt(24,750,"Pudar rakaman" if M else "Recording fade",15,SUB,True)
    for i,t in enumerate(["Tiada" if M else "None","Awal" if M else "Start","Akhir" if M else "End","Kedua" if M else "Both"]): s.btn(24+i*124,776,116,44,t,gold=i==3,sz=15)
    s.mark(500,400); s.mark(500,580); s.mark(500,758)
    return s
def looks(L):
    s=director(L,True); M=L=="ms"; sheet(s,"Gaya visual" if M else "Visual style")
    s.txt(24,392,"Gaya" if M else "Look",15,SUB,True)
    names=["Standard","Supermassive","Super","Fantastic","Superior","Cinematic","Old School","Warm Kitchen","Food Pop"]
    for i,t in enumerate(names): s.btn(24+(i%3)*166,420+(i//3)*60,158,50,t,gold=i==1,sz=14)
    s.txt(24,620,"Susun atur" if M else "Layout",15,SUB,True)
    for i,t in enumerate(["Penuh" if M else "Full","Belah" if M else "Split","Bingkai kecil" if M else "Small frame"]): s.btn(24+i*166,648,158,50,t,gold=i==0,sz=14)
    s.mark(270,410); s.mark(270,640)
    return s
def brand(L):
    s=S(); M=L=="ms"
    s.box(0,0,W,380,(18,17,15),0); s.pic(110,20,320,340,(60,80,70),(25,35,30))
    s.d.ellipse([360,40,420,100],fill=(240,240,230)); s.txt(372,62,"LOGO",12,(40,90,60),True)
    s.box(130,290,280,56,(255,255,255,),14); s.txt(146,296,"RM12  Nasi Lemak",17,(30,30,30),True); s.txt(146,320,"012-345 6789",14,(80,80,80))
    s.txt(24,400,"Gaya jenama" if M else "Brand style",22,TXT,True)
    for i in range(6): s.btn(24+(i%3)*166,440+(i//3)*64,158,54,("Gaya " if M else "Style ")+str(i+1),gold=i==0,sz=15)
    s.txt(24,590,"Bila dipaparkan" if M else "When to show",15,SUB,True)
    for r,lab in enumerate(["Logo",("Harga" if M else "Price"),("Telefon" if M else "Phone")]):
        y=620+r*70; s.txt(24,y+12,lab,16,TXT,True)
        for i,t in enumerate(["Sentiasa" if M else "Always","Tiada" if M else "Hidden","Ikut masa" if M else "Timed"]): s.btn(150+i*124,y,116,46,t,gold=i==0,sz=13)
    s.mark(390,30); s.mark(270,280); s.mark(500,430); s.mark(500,610)
    return s
def videos(L):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,38,"Videos",24,TXT,True)
    s.btn(24,100,240,56,"Video & Gambar" if M else "Video & Photos",gold=True,sz=15); s.btn(276,100,240,56,"Draf" if M else "Drafts",sz=16)
    s.btn(24,166,492,50,"Import",sz=15)
    for i in range(4):
        y=236+i*170; s.box(24,y,492,156,CARD,16); s.pic(36,y+12,100,132); s.txt(150,y+20,"Video_0%d" % (18-i),17,TXT,True); s.txt(150,y+48,"0:%02d · 1080p" % (40-i*7),14,SUB)
        for j,t in enumerate(["Main" if M else "Play","Edit","Kongsi" if M else "Share"]): s.btn(150+j*118,y+92,110,46,t,sz=13)
    s.mark(144,92); s.mark(396,92); s.mark(270,174); s.mark(380,320)
    return s
def editor(L, fx=False):
    s=S(); M=L=="ms"
    s.btn(18,24,56,52,"X",sz=18); s.btn(82,24,80,52,"9:16",sz=15); s.btn(300,24,56,52,"↶",sz=18); s.btn(362,24,56,52,"↷",sz=18); s.btn(426,24,96,52,"Eksport" if M else "Export",gold=True,sz=14)
    s.pic(150,92,240,420)
    s.txt(24,530,"00:11 / 00:30",16,TXT,True); s.btn(220,522,100,40,"▶",sz=16)
    s.box(0,576,W,180,(20,19,17),0)
    for i in range(5): s.pic(20+i*104,600,98,70,(120,90,60),(60,50,40))
    s.box(20,690,500,34,(36,90,58),10); s.txt(30,696,"♪ " + ("Ceria" if M else "Happy"),14,TXT,True)
    s.d.line([(270,590),(270,750)],fill=(255,255,255),width=3)
    tools = (["Keyframe","Stabil" if M else "Steady","Latar kabur" if M else "Blur sides","Buang latar" if M else "Remove bg","Terbalik" if M else "Reverse"] if fx
             else ["Templat" if M else "Templates","Potong" if M else "Cut","Audio","Suara video" if M else "Video sound","Teks" if M else "Text"])
    for i,t in enumerate(tools): s.btn(10+i*106,790,100,90,t,sz=12)
    if fx: s.mark(60,782); s.mark(166,782); s.mark(272,782); s.mark(378,782); s.mark(484,782)
    else: s.mark(474,50); s.mark(270,585); s.mark(60,782); s.mark(166,782); s.mark(378,782)
    return s
def templates(L):
    s=S(); M=L=="ms"
    s.txt(24,30,"Templat" if M else "Templates",26,TXT,True); s.btn(456,22,60,52,"X",sz=18)
    s.btn(24,90,240,50,"Simpan saya" if M else "Save mine",sz=15); s.btn(276,90,240,50,"Kongsi" if M else "Share",gold=True,sz=15)
    s.box(24,152,492,48,(22,21,18),14,LINE); s.txt(40,164,"Cari templat" if M else "Search templates",15,SUB)
    for i,t in enumerate(["Untuk anda" if M else "For you","Super","Makanan" if M else "Food","Jualan" if M else "Sales"]): s.btn(24+i*124,212,116,42,t,gold=i==0,sz=13)
    cols=[((200,90,40),(60,26,14)),((45,60,140),(12,15,36)),((200,51,30),(43,11,18)),((45,122,120),(14,35,36))]
    names=["Semua fungsi" if M else "Everything","Super sinematik" if M else "Super cinematic","Super promosi" if M else "Super promo","Diari makan" if M else "Food diary"]
    for i in range(4):
        x=24+(i%2)*250; y=272+(i//2)*340; s.pic(x,y,242,270,*cols[i]); s.txt(x+10,y+240,"16 · 34s",13,TXT,True)
        s.txt(x,y+278,names[i],16,TXT,True); s.txt(x,y+302,"@promulticam",13,SUB)
    s.mark(394,82); s.mark(270,204); s.mark(145,262)
    return s
def chat(L):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,38,"Sembang komuniti" if M else "Community chat",24,TXT,True)
    msgs=[(False,"@chef_aminah","Ada tip cahaya untuk masak waktu malam?" if M else "Any light tips for cooking at night?"),
          (True,"","Guna gaya Supermassive, sangat terang!" if M else "Use the Supermassive look, very bright!"),
          (False,"@laskar7","Templat Diari makan cantik" if M else "The Food diary template is nice")]
    y=110
    for mine,u,t in msgs:
        w=360; x=W-24-w if mine else 24; h=88 if u else 64
        s.box(x,y,w,h,(58,48,32) if mine else CARD,16,GOLD if mine else None)
        if u: s.txt(x+14,y+10,u,13,GOLD,True)
        s.txt(x+14,y+(34 if u else 20),t,14,TXT); y+=h+16
    s.box(24,860,370,60,(22,21,18),14,LINE); s.txt(40,878,"Tulis sesuatu yang baik…" if M else "Say something nice…",15,SUB)
    s.btn(404,860,112,60,"Hantar" if M else "Send",gold=True,sz=16)
    s.mark(60,112); s.mark(36,858); s.mark(500,852)
    return s
def plans(L):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,38,"Plans",24,TXT,True)
    s.box(24,100,492,60,CARD,16); s.txt(40,118,"Pro percuma: tinggal 7 hari." if M else "Free Pro: 7 day(s) left.",16,GOLD,True)
    cards=[("Asas (percuma)" if M else "Basic (free)","RM0",False),("Bulanan" if M else "Monthly","RM—  "+("sebulan" if M else "per month"),True),("Tahunan" if M else "Yearly","RM—  "+("setahun" if M else "per year"),True)]
    y=180
    for n,p,pro in cards:
        s.box(24,y,492,200,CARD,16); s.txt(40,y+16,n,19,TXT,True)
        if pro: s.box(40+s.d.textlength(n,font=f(19,True))+12,y+18,46,24,(38,33,23),8,GOLD); s.txt(52+s.d.textlength(n,font=f(19,True))+8,y+21,"PRO",12,GOLD,True)
        s.txt(40,y+50,p,22,GOLD,True); s.txt(40,y+90,"• " + ("6 kamera, 1080p, semua gaya" if M and pro else "6 cameras, 1080p, all looks" if pro else "Pengarah + 1 kamera" if M else "Director + 1 camera"),14,TXT)
        s.btn(40,y+134,460,50,("Pilih " if M else "Choose ")+n if pro else ("Pelan semasa" if M else "Current plan"),gold=pro,sz=15); y+=216
    s.mark(36,96); s.mark(500,396); s.mark(500,612)
    return s
def update(L):
    s=S(); M=L=="ms"
    s.btn(18,26,56,56,"<",sz=22); s.txt(86,38,"App Update",24,TXT,True)
    s.box(24,110,492,250,CARD,16); s.txt(40,130,"Versi semasa 0.1.90" if M else "Current version 0.1.90",16,SUB)
    s.txt(40,164,"Versi baru 0.1.91 (40 MB)" if M else "New version 0.1.91 (40 MB)",18,GOLD,True)
    s.box(40,210,460,16,(50,46,38),8); s.box(40,210,300,16,GOLD,8); s.txt(40,236,"65%",14,TXT,True)
    s.btn(40,284,460,56,"Pasang" if M else "Install",gold=True,sz=17)
    s.box(24,400,492,120,(28,27,24),16); s.txt(40,418,"ProMultiCam",15,TXT,True); s.txt(40,446,"Versi baru sedia — tekan untuk pasang" if M else "New version ready — tap to install",14,SUB)
    s.mark(36,200); s.mark(500,276); s.mark(500,392)
    return s

CAPS = {
 "m_home": (["Pengarah","Kamera","Sembang","Templat"],["Director","Camera","Chat","Template"]),
 "m_director": (["ID Studio","Tambah kamera (QR)","Kotak sudut: tekan untuk tukar","Kamera depan/belakang","Tetapan rakaman","Gaya visual"],["Studio ID","Add camera (QR)","Angle tiles: tap to switch","Front/back camera","Recording settings","Visual style"]),
 "m_join": (["Taip ID Studio","Sertai","Atau imbas QR"],["Type the Studio ID","Join","Or scan the QR"]),
 "m_record": (["Jeda / Sambung","Tamat rakaman","Masa rakaman"],["Pause / Resume","Stop recording","Recording time"]),
 "m_settings": (["Tukar automatik + contoh","Kesan tukar (Auto = rawak)","Pudar rakaman"],["Auto switch + example","Switch effect (Auto = random)","Recording fade"]),
 "m_looks": (["Gaya (Supermassive)","Susun atur"],["Look (Supermassive)","Layout"]),
 "m_brand": (["Logo","Harga & telefon","6 gaya jenama","Bila dipaparkan"],["Logo","Price & phone","6 brand styles","When to show"]),
 "m_videos": (["Edit video & gambar","Draf","Import dari galeri","Main / Edit / Kongsi"],["Edit video & photos","Drafts","Import from gallery","Play / Edit / Share"]),
 "m_editor": (["Eksport","Garisan: klip yang diubah","Templat","Potong","Suara video buka/tutup"],["Export","Line: the clip being changed","Templates","Cut","Video sound on/off"]),
 "m_editorfx": (["Keyframe","Stabil","Latar kabur","Buang latar (AI)","Terbalik"],["Keyframe","Steady","Blur sides","Remove background (AI)","Reverse"]),
 "m_templates": (["Simpan / Kongsi","Cari","Kumpulan"],["Save / Share","Search","Groups"]),
 "m_chat": (["Nama pengguna","Tulis mesej","Hantar"],["User name","Write a message","Send"]),
 "m_plans": (["Pelan semasa & baki hari","Bulanan","Tahunan"],["Current plan & days left","Monthly","Yearly"]),
 "m_update": (["Muat turun automatik","Pasang","Notifikasi: tekan untuk pasang"],["Downloads by itself","Install","Notification: tap to install"]),
}
FN = {"m_home":home,"m_director":director,"m_join":join,"m_record":record,"m_settings":settings,"m_looks":looks,"m_brand":brand,"m_videos":videos,"m_editor":editor,
      "m_editorfx":lambda L: editor(L,True),"m_templates":templates,"m_chat":chat,"m_plans":plans,"m_update":update}
for k,fn in FN.items():
    for L in ("ms","en"):
        s=fn(L); assert len(s.marks)==len(CAPS[k][0]),(k,len(s.marks)); s.save(f"{k}_{L}.jpg")
json.dump(CAPS,open(os.path.join(OUT,"caps.json"),"w"),ensure_ascii=False)
print("ok")
