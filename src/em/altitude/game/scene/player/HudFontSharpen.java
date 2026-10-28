package em.altitude.game.scene.player;

import em.opengl.render.RU;
import em.opengl.resource.font.CharInfo;
import em.opengl.resource.font.Font;
import em.opengl.resource.font.builder.FontList;
import em.opengl.resource.font.builder.FontRenderType;
import em.opengl.resource.font.builder.JavaFontConfig;
import em.opengl.resource.font.builder.PsFontConfig;
import em.opengl.resource.font.builder.PsFontStyle;
import em.opengl.resource.image.Texture;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * Sharp HUD text. The stock fonts are bitmaps made for the 1280x720 HUD canvas, which is scaled up to the display
 * (3x at 3840x2160), so every font pixel becomes a 3x3 block. Font.render is hooked: each glyph is drawn from an
 * atlas rasterized here at k times the size (k = HUD scale rounded up, 2-4), scaled down by k. The font's family,
 * size, style, outline or stroke and colors come from the game's own FontList entry for its bitmap. Advances, widths
 * and heights stay those of the stock font, so layout and alignment do not move; each hi-res glyph is centered in
 * the stock glyph's box, and the baseline and size are calibrated against the stock glyph boxes (medians), which
 * absorbs the stock builders' fixed offsets. The atlas is rasterized off the render thread (stock text until it is
 * ready), uploaded with mipmaps, and rebuilt when the HUD scale changes. Families not installed in Windows (Blambot
 * Casual) load from .ttf files bundled in the client jar. Strings with a character the face cannot draw keep the
 * stock bitmap. /opt stockfonts ON or -Dclient.sharpfonts=false keeps the stock bitmaps.
 */
public final class HudFontSharpen {
    private HudFontSharpen(){}
    static final boolean DISABLED="false".equals(System.getProperty("client.sharpfonts"));
    static volatile boolean stock;
    static final Set<String> FAMILIES=Set.of("Verdana","Arial","Courier New","Blambot Casual");
    /** Edge treatment: none, the stock 8-neighbour outline, a one-sided drop shadow or a round Photoshop stroke. */
    enum Edge{NONE,OUTLINE,SHADOW,STROKE}
    /** What to rasterize for one stock bitmap. */
    static final class Spec {
        final String name,family;
        final int size,style,stroke,fill,edge;
        final Edge kind;
        Spec(String name,String family,int size,int style,Edge kind,int stroke,int fill,int edge){
            this.name=name;this.family=family;this.size=size;this.style=style;this.kind=kind;this.stroke=stroke;this.fill=fill;this.edge=edge;
        }
    }
    /** The shipped Verdana_16_bold_outline bitmap has regular-weight strokes (1-pixel stems, unlike the bold 11). */
    static final Set<String> REGULAR_WEIGHT=Set.of("Verdana_16_bold_outline_1798559992");
    /** Kept stock: Arial_30_Regular is a horizontally condensed Photoshop bitmap, used only by the demo-expired window. */
    static final Set<String> EXCLUDED=Set.of("Arial_30_Regular_1317104934");
    static final int PAD=4,ATLAS_WIDTH=1024;
    private static Map<String,Spec> specs;
    private static final Map<Font,Sharp> SHARP=new WeakHashMap<>();
    private static final Sharp NONE=new Sharp(null,0,0,0);
    private static ExecutorService worker;
    /** Atlases of recreated GL contexts, kept referenced so the Texture finalizer never reports their stale ids as leaked. */
    private static final List<Sharp> STALE=new ArrayList<>();
    private static final Field INFO=field("infoByChar"),BASE_X=field("baselineOffsetX"),BASE_Y=field("baselineOffsetY");

    private static Field field(String name){try{Field f=Font.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(ReflectiveOperationException|RuntimeException e){return null;}}

    /** Once per frame, from {@link ClientFrame}: /opt stockfonts. */
    static void setStock(boolean on){stock=on;}

    /** Every stock bitmap the game's FontList builds, by bitmap name, for the families handled here. */
    static synchronized Map<String,Spec> specs(){
        if(specs!=null)return specs;
        Map<String,Spec> m=new HashMap<>();
        for(JavaFontConfig c:FontList.ALL_JAVA_FONT_CONFIGS){
            if(!FAMILIES.contains(c.name))continue;
            Edge kind=c.renderType==FontRenderType.OUTLINE?Edge.OUTLINE:c.renderType==FontRenderType.DROP_SHADOW?Edge.SHADOW:Edge.NONE;
            int style=REGULAR_WEIGHT.contains(c.getName())?java.awt.Font.PLAIN:c.style.javaAwtFontConstant;
            m.put(c.getName(),new Spec(c.getName(),c.name,c.size,style,kind,0,c.characterColor.getRGB(),c.outlineColor.getRGB()));
        }
        for(PsFontConfig c:FontList.ALL_PS_FONT_CONFIGS){
            if(!FAMILIES.contains(c.fontName))continue;
            int style=c.fontStyle==PsFontStyle.BOLD?java.awt.Font.BOLD:c.fontStyle==PsFontStyle.ITALIC?java.awt.Font.ITALIC:c.fontStyle==PsFontStyle.BOLD_ITALIC?java.awt.Font.BOLD|java.awt.Font.ITALIC:java.awt.Font.PLAIN;
            m.put(c.getName(),new Spec(c.getName(),c.fontName,c.fontSize,style,c.strokeThickness>0?Edge.STROKE:Edge.NONE,c.strokeThickness,c.fontColor.getRGB(),c.strokeColor.getRGB()));
        }
        m.keySet().removeAll(EXCLUDED);
        return specs=m;
    }

    /** One rasterized glyph: quad offsets in stock font pixels (y up from the baseline) and texture coordinates. */
    static final class Glyph {
        final float y0,w,h,u0,v0,u1,v1;
        Glyph(float y0,float w,float h,float u0,float v0,float u1,float v1){this.y0=y0;this.w=w;this.h=h;this.u0=u0;this.v0=v0;this.u1=u1;this.v1=v1;}
    }
    /** Rasterized atlas (pixels until uploaded) for one font at one scale, with the calibration that was applied. */
    static final class Atlas {
        final int width,height;
        final int[] argb;
        final Map<Character,Glyph> glyphs;
        final float sizeRatio,shiftY;
        Atlas(int width,int height,int[] argb,Map<Character,Glyph> glyphs,float sizeRatio,float shiftY){
            this.width=width;this.height=height;this.argb=argb;this.glyphs=glyphs;this.sizeRatio=sizeRatio;this.shiftY=shiftY;
        }
    }
    /** One stock font's sharp replacement at HUD scale k: rasterizing (pending), ready (texture) or failed. */
    static final class Sharp {
        final Spec spec;
        final int k,baseX,baseY;
        Future<Atlas> pending;
        Atlas atlas;
        Texture texture;
        Object context;
        boolean failed;
        Sharp(Spec spec,int k,int baseX,int baseY){this.spec=spec;this.k=k;this.baseX=baseX;this.baseY=baseY;}
    }

    /** HUD scale rounded up for the current window: 1 (no change) below 1.25x, otherwise 2-4. */
    static int scale(){
        try{float s=Math.min(Display.getWidth()/1280f,Display.getHeight()/720f);return s<1.25f?1:Math.min(4,(int)Math.ceil(s-0.05f));}
        catch(RuntimeException|LinkageError e){return 1;}
    }

    /**
     * Hook: start of Font.reloadImage. In the same GL context (the HD sprite reload) the atlas is still valid and is
     * kept: it is not one of the game's textures, so nothing freed it. After a new context (display change) its id is
     * stale: forget it so it is uploaded again.
     */
    public static void reload(Font font){
        synchronized(SHARP){
            Sharp s=SHARP.get(font);
            if(s==null||s.texture!=null&&s.context!=null&&s.context==context())return;
            SHARP.remove(font);
            if(s.texture!=null)STALE.add(s);
        }
    }

    /** The current GL context's capabilities object: one per context, so it tells contexts apart. */
    static Object context(){try{return GLContext.getCapabilities();}catch(RuntimeException|LinkageError e){return null;}}

    /** Hook: start of Font.render(str,x,y,align,maxAdvance); true when drawn here and the stock render is skipped. */
    public static boolean render(Font font,CharSequence str,int x,int y,int align,int maxAdvance){
        if(DISABLED||stock||INFO==null||BASE_X==null||BASE_Y==null)return false;
        Sharp sharp=sharp(font);
        if(sharp==null||sharp.texture==null)return false;
        Map<Character,Glyph> glyphs=sharp.atlas.glyphs;
        Map<?,?> info=info(font);
        if(info==null)return false;
        Glyph fallback=glyphs.get('X');
        if(str.length()==0||str.length()==1&&str.charAt(0)==' ')return true;
        for(int i=0;i<str.length();i++){char ch=str.charAt(i);if(!Character.isWhitespace(ch)&&ch!=' '&&(info.containsKey(ch)?glyphs.get(ch):fallback)==null)return false;}
        x+=sharp.baseX;y+=sharp.baseY;
        if((align&Font.HCENTER)!=0)x-=font.getWidth(str)>>1;else if((align&Font.RIGHT)!=0)x-=font.getWidth(str);
        if((align&Font.VCENTER)!=0)y-=font.getHeight()>>1;else if((align&Font.TOP)!=0)y-=font.getHeight();
        RU.push();RU.translate((float)x,(float)y);
        RU.begin(GL11.GL_QUADS,sharp.texture);
        int pen=0;
        for(int i=0;i<str.length();i++){
            char ch=str.charAt(i);CharInfo c=font.getCharInfo(ch);
            int next=pen+c.getAdvance();
            if(maxAdvance!=0&&next>maxAdvance)break;
            if(!Character.isWhitespace(ch)&&ch!=' '){
                Glyph g=info.containsKey(ch)?glyphs.get(ch):fallback;
                // Hinted small sizes are narrower: a wider glyph is squeezed into the stock box, so a
                // neighbour's outline never covers it; a narrower one is centered.
                float w=Math.min(g.w,c.getImageWidth()),left=pen+c.getAlignOffsetX()+(c.getImageWidth()-w)*0.5f;
                // GL texture coordinates directly: RU.texCoord flips v for the game's bottom-up images.
                GL11.glTexCoord2f(g.u0,g.v1);RU.vertex(left,g.y0);
                GL11.glTexCoord2f(g.u1,g.v1);RU.vertex(left+w,g.y0);
                GL11.glTexCoord2f(g.u1,g.v0);RU.vertex(left+w,g.y0+g.h);
                GL11.glTexCoord2f(g.u0,g.v0);RU.vertex(left,g.y0+g.h);
            }
            pen=next;
        }
        RU.end();RU.pop();
        return true;
    }

    private static Map<?,?> info(Font font){try{return (Map<?,?>)INFO.get(font);}catch(ReflectiveOperationException|RuntimeException e){return null;}}

    /** Bitmap name from Font.toString(), "Font[fonts/NAME.png]". */
    static String bitmapName(Font font){
        String s=font.toString();int a=s.lastIndexOf('/'),b=s.lastIndexOf('.');
        return a>=0&&b>a?s.substring(a+1,b):s;
    }

    /** Stock glyph boxes by character: width, height and baseline offset of the bottom edge. */
    static Map<Character,int[]> boxes(Map<?,?> info){
        Map<Character,int[]> m=new TreeMap<>();
        for(Map.Entry<?,?> e:info.entrySet()){CharInfo c=(CharInfo)e.getValue();m.put((Character)e.getKey(),new int[]{c.getImageWidth(),c.getImageHeight(),c.getAlignOffsetY()});}
        return m;
    }

    /** The ready atlas for this font at the current scale; starts rasterizing or uploads when needed. Null: stock. */
    private static Sharp sharp(Font font){
        Sharp s;synchronized(SHARP){s=SHARP.get(font);}
        int k=scale();
        if(s==NONE)return null;
        if(s!=null&&s.k!=k&&s.texture!=null){s.texture.free();s=null;}
        else if(s!=null&&s.k!=k&&s.pending==null)s=null;
        if(s==null){
            Spec spec=specs().get(bitmapName(font));
            if(spec==null||k<2){synchronized(SHARP){SHARP.put(font,spec==null?NONE:new Sharp(spec,k,0,0));}return null;}
            Map<?,?> info=info(font);
            try{s=new Sharp(spec,k,BASE_X.getInt(font),BASE_Y.getInt(font));}catch(ReflectiveOperationException|RuntimeException e){return null;}
            Map<Character,int[]> boxes=boxes(info);final int scale=k;
            synchronized(HudFontSharpen.class){
                if(worker==null)worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"client-fonts");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
                s.pending=worker.submit(()->rasterize(spec,scale,boxes));
            }
            synchronized(SHARP){SHARP.put(font,s);}
            return null;
        }
        if(s.failed)return null;
        if(s.texture==null){
            if(s.pending==null||!s.pending.isDone())return null;
            try{s.atlas=s.pending.get();s.texture=upload(s.atlas);s.context=context();}catch(Exception|LinkageError e){s.failed=true;System.err.println("HudFontSharpen: stock "+s.spec.name+" kept: "+e);return null;}
            s.pending=null;
        }
        return s;
    }

    /** Upload with mipmaps (smooth when the HUD is drawn smaller than k). */
    static Texture upload(Atlas a){
        ByteBuffer rgba=BufferUtils.createByteBuffer(a.width*a.height*4);
        for(int p:a.argb)rgba.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)(p>>>24));
        rgba.flip();
        // Bind through RU so its cached texture id matches what GL has bound.
        Texture texture=new Texture(GL11.glGenTextures(),a.width,a.height,null);RU.setActiveTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,0x8191,GL11.GL_TRUE);// GL_GENERATE_MIPMAP
        // Tight rows from the start of the buffer, whatever pixel-store state the game's own uploads left.
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try{
            for(int p:new int[]{GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_ROWS,GL11.GL_UNPACK_SKIP_PIXELS,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_ROWS,GL11.GL_PACK_SKIP_PIXELS})GL11.glPixelStorei(p,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,4);GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,4);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES,0);GL11.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST,0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,a.width,a.height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,rgba);
            // Read level 0 back: anything but an exact copy keeps the stock font.
            ByteBuffer back=BufferUtils.createByteBuffer(a.width*a.height*4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,back);
            rgba.rewind();
            if(!back.equals(rgba)){texture.free();throw new IllegalStateException("Font atlas upload did not read back");}
        }finally{GL11.glPopClientAttrib();}
        return texture;
    }

    /** Fonts Windows does not have, bundled in the client jar next to this class (fonts/, licensed for Altitude). */
    static final List<String> BUNDLED=List.of("Blambot Casual Regular.ttf","Blambot Casual Bold.ttf");

    /**
     * The face at size 1 with the remaining style to apply: an installed family, or a bundled .ttf whose family
     * matches (its Bold file for a bold style, then the style is not synthesized again).
     */
    static java.awt.Font face(String family,int style){
        java.awt.Font installed=new java.awt.Font(family,style,1);
        if(installed.getFamily(Locale.ROOT).equalsIgnoreCase(family))return installed;
        java.awt.Font regular=null,bold=null;
        for(String name:BUNDLED){
            try(InputStream in=HudFontSharpen.class.getResourceAsStream("fonts/"+name)){
                if(in==null)continue;
                java.awt.Font font=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,in);
                if(!font.getFamily(Locale.ROOT).equalsIgnoreCase(family))continue;
                if(font.getFontName(Locale.ROOT).toLowerCase(Locale.ROOT).contains("bold"))bold=font;else regular=font;
            }catch(Exception e){/* not a usable font file */}
        }
        if((style&java.awt.Font.BOLD)!=0&&bold!=null)return bold.deriveFont(style&~java.awt.Font.BOLD,1f);
        if(regular!=null)return regular.deriveFont(style,1f);
        throw new IllegalStateException("Font not installed and not bundled: "+family);
    }

    /**
     * Rasterize at k times the stock size, antialiased: fill color, then the stock edge scaled by k (8-neighbour
     * outline, drop shadow, or a round stroke of the Photoshop thickness). Calibrated against the stock boxes: the
     * size by the median height ratio (redone once when off by over 5%; small sizes round heights by up to 3%), the baseline by the median bottom offset.
     */
    static Atlas rasterize(Spec spec,int k,Map<Character,int[]> stock){
        java.awt.Font face=face(spec.family,spec.style);
        float size=spec.size;
        Map<Character,int[]> boxes=new LinkedHashMap<>();Map<Character,int[]> pixels=new HashMap<>();
        float ratio=1;
        for(int pass=0;pass<2;pass++){
            boxes.clear();pixels.clear();
            draw(face.deriveFont(size*k),spec,k,stock.keySet(),boxes,pixels);
            List<Float> ratios=new ArrayList<>();
            for(Map.Entry<Character,int[]> e:boxes.entrySet()){int[] s=stock.get(e.getKey());if(s!=null&&s[1]>=8)ratios.add(s[1]/(e.getValue()[1]/(float)k));}
            ratio=median(ratios,1);
            if(pass>0||Math.abs(ratio-1)<=0.05f)break;
            size*=ratio;
        }
        List<Float> shifts=new ArrayList<>();
        for(Map.Entry<Character,int[]> e:boxes.entrySet()){int[] s=stock.get(e.getKey());if(s!=null)shifts.add(s[2]-e.getValue()[2]/(float)k);}
        float shift=median(shifts,0);
        // Shelf packing, tallest first, PAD transparent pixels around each glyph for filtering and mipmaps.
        List<Character> order=new ArrayList<>(boxes.keySet());order.sort((a,b)->boxes.get(b)[1]-boxes.get(a)[1]);
        int width=ATLAS_WIDTH;for(int[] b:boxes.values())width=Math.max(width,b[0]+2*PAD);
        Map<Character,int[]> at=new HashMap<>();int x=PAD,y=PAD,row=0;
        for(char c:order){int[] b=boxes.get(c);if(x+b[0]+PAD>width){x=PAD;y+=row+PAD;row=0;}at.put(c,new int[]{x,y});x+=b[0]+PAD;row=Math.max(row,b[1]);}
        int height=y+row+PAD;
        int[] argb=new int[width*height];
        // Transparent texels carry the edge color (or the fill color without an edge), as the stock atlases do.
        Arrays.fill(argb,(spec.kind==Edge.NONE?spec.fill:spec.edge)&0xFFFFFF);
        Map<Character,Glyph> glyphs=new HashMap<>();
        for(char c:order){
            int[] b=boxes.get(c),p=at.get(c);int[] img=pixels.get(c);
            for(int yy=0;yy<b[1];yy++)System.arraycopy(img,yy*b[0],argb,(p[1]+yy)*width+p[0],b[0]);
            glyphs.put(c,new Glyph(b[2]/(float)k+shift,b[0]/(float)k,b[1]/(float)k,p[0]/(float)width,p[1]/(float)height,(p[0]+b[0])/(float)width,(p[1]+b[1])/(float)height));
        }
        return new Atlas(width,height,argb,Collections.unmodifiableMap(glyphs),size/spec.size,shift);
    }

    private static float median(List<Float> values,float empty){
        if(values.isEmpty())return empty;
        Collections.sort(values);return values.get(values.size()/2);
    }

    /** Glyph images (tight bounds) and boxes: width, height, bottom edge above the baseline, in k-pixels. */
    private static void draw(java.awt.Font font,Spec spec,int k,Set<Character> chars,Map<Character,int[]> boxes,Map<Character,int[]> pixels){
        int s=Math.round(font.getSize2D()),margin=4*k+2*spec.stroke*k,bw=s*2+2*margin,bh=s*2+2*margin,drawX=margin,drawY=s*7/5+margin;
        BufferedImage buffer=new BufferedImage(bw,bh,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=buffer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
        FontRenderContext frc=g.getFontRenderContext();
        Color fill=new Color(spec.fill,true),edge=new Color(spec.edge,true);
        for(char c:chars){
            if(Character.isWhitespace(c)||!font.canDisplay(c))continue;
            g.setComposite(java.awt.AlphaComposite.Clear);g.fillRect(0,0,bw,bh);g.setComposite(java.awt.AlphaComposite.SrcOver);
            TextLayout layout=new TextLayout(String.valueOf(c),font,frc);
            if(spec.kind==Edge.OUTLINE){g.setColor(edge);for(int dx=-k;dx<=k;dx++)for(int dy=-k;dy<=k;dy++)if(dx!=0||dy!=0)layout.draw(g,drawX+dx,drawY+dy);}
            else if(spec.kind==Edge.SHADOW){g.setColor(edge);for(int dx=0;dx<=k;dx++)for(int dy=0;dy<=k;dy++)if(dx!=0||dy!=0)layout.draw(g,drawX+dx,drawY+dy);}
            Shape shape=layout.getOutline(AffineTransform.getTranslateInstance(drawX,drawY));
            if(spec.kind==Edge.STROKE){g.setColor(edge);g.setStroke(new BasicStroke(2f*spec.stroke*k,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(shape);}
            g.setColor(fill);g.fill(shape);
            int[] px=buffer.getRGB(0,0,bw,bh,null,0,bw);
            int left=bw,right=-1,top=bh,bottom=-1;
            for(int yy=0;yy<bh;yy++)for(int xx=0;xx<bw;xx++)if((px[yy*bw+xx]>>>24)!=0){left=Math.min(left,xx);right=Math.max(right,xx);top=Math.min(top,yy);bottom=Math.max(bottom,yy);}
            if(right<0||left==0||top==0||right==bw-1||bottom==bh-1)continue;
            int w=right-left+1,h=bottom-top+1;int[] img=new int[w*h];
            for(int yy=0;yy<h;yy++)System.arraycopy(px,(top+yy)*bw+left,img,yy*w,w);
            boxes.put(c,new int[]{w,h,drawY-(bottom+1)});pixels.put(c,img);
        }
        g.dispose();
    }
}
