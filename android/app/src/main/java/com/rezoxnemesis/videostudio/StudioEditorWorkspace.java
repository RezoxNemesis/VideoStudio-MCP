package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.Function;

/** Layout only: native editing, source ownership and revision guards stay in MainActivity. */
public final class StudioEditorWorkspace extends LinearLayout {
    public static final int BG=0xff171e1b,PANEL=0xff1d2722,TEXT=0xffedf0e8,MUTED=0xffa2aca4,RULE=0xff35423a,SAGE=0xffd6e4cc;
    private static final String[] PANES={"Media","Timeline","Inspector"};
    private final FrameLayout pane;
    private final PaneTabs tabs;
    private final Function<String,View> renderPane;
    private final Consumer<String> selected;
    private final View headerView,previewView,transportView,editingToolsView;
    private final LinearLayout lowerView;
    private LinearLayout viewingPanel;
    private boolean landscape;
    private int preferredPreviewHeight;
    private float landscapeShare;
    private final SplitHandle resizeHandle;
    private static final String SIZING_PREFS="videostudio_editor_workspace_size_v1";
    private String activePane;

    public StudioEditorWorkspace(Context context,View header,View preview,View transport,View editingTools,
                                 String initialPane,Function<String,View> renderPane,
                                 Consumer<String> selected){
        super(context);this.renderPane=renderPane;this.selected=selected;
        headerView=header;previewView=preview;transportView=transport;editingToolsView=editingTools;
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        int available=getResources().getConfiguration().screenHeightDp;
        android.content.SharedPreferences sizes=context.getSharedPreferences(SIZING_PREFS,Context.MODE_PRIVATE);
        preferredPreviewHeight=dp(sizes.getInt("portraitPreviewDp",available>0&&available<720?176:280));
        landscapeShare=Math.max(.30f,Math.min(.72f,sizes.getInt("landscapeSharePercent",48)/100f));
        resizeHandle=new SplitHandle(context);
        setOrientation(VERTICAL);setBackgroundColor(BG);setPadding(dp(12),dp(6),dp(12),dp(6));
        header.setMinimumHeight(dp(56));
        pane=new FrameLayout(context);pane.setContentDescription("Editor workspace pane");
        tabs=new PaneTabs(context,this::showPane);
        lowerView=new LinearLayout(context);lowerView.setOrientation(VERTICAL);
        lowerView.addView(tabs,new LayoutParams(-1,-2));lowerView.addView(pane,new LayoutParams(-1,0,1));
        arrangeExistingViews();
        showPane(java.util.Arrays.asList(PANES).contains(initialPane)?initialPane:"Timeline");
    }

    /** Reflow the same admitted player and pane; configuration changes are not edits. */
    private void arrangeExistingViews(){
        for(View view:new View[]{headerView,previewView,transportView,lowerView,editingToolsView,resizeHandle})
            if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
        removeAllViews();addView(headerView,new LayoutParams(-1,-2));
        if(landscape){
            LinearLayout body=new LinearLayout(getContext());body.setOrientation(HORIZONTAL);
            LinearLayout viewing=new LinearLayout(getContext());viewing.setOrientation(VERTICAL);
            viewingPanel=viewing;
            previewView.setMinimumHeight(dp(96));viewing.addView(previewView,new LayoutParams(-1,0,1));
            viewing.addView(transportView,new LayoutParams(-1,-2));
            LayoutParams viewingSize=new LayoutParams(0,-1,landscapeShare);
            body.addView(viewing,viewingSize);
            resizeHandle.setVertical(true);
            body.addView(resizeHandle,new LayoutParams(dp(22),-1));
            body.addView(lowerView,new LayoutParams(0,-1,1-landscapeShare));
            addView(body,new LayoutParams(-1,0,1));
        }else{
            viewingPanel=null;
            addView(previewView,new LayoutParams(-1,preferredPreviewHeight));
            resizeHandle.setVertical(false);
            addView(resizeHandle,new LayoutParams(-1,dp(21)));
            addView(transportView,new LayoutParams(-1,-2));addView(lowerView,new LayoutParams(-1,0,1));
        }
        addView(editingToolsView,new LayoutParams(-1,-2));
    }

    @Override protected void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        boolean nextLandscape=configuration.orientation==Configuration.ORIENTATION_LANDSCAPE;
        if(nextLandscape!=landscape){landscape=nextLandscape;arrangeExistingViews();}
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        if(!landscape&&MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED){
            int width=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
            int fixed=getPaddingTop()+getPaddingBottom()+dp(21)+naturalHeight(headerView,width)
                    +naturalHeight(transportView,width)+naturalHeight(tabs,width)+naturalHeight(editingToolsView,width);
            // Keep the actual Timeline heading and native two-band track reachable. Generic
            // touch styling may make the preview WRAP_CONTENT because it owns Retry; the
            // workspace, rather than the player's aspect ratio, owns this screen's budget.
            int previewBudget=MeasureSpec.getSize(heightSpec)-fixed-dp(48+135+8);
            previewView.getLayoutParams().height=Math.max(dp(96),Math.min(preferredPreviewHeight,previewBudget));
        }
        super.onMeasure(widthSpec,heightSpec);
    }

    public boolean isWideWorkspace(){return landscape;}
    public float landscapePreviewFraction(){return landscapeShare;}
    public int portraitPreviewHeightPx(){return preferredPreviewHeight;}

    /** Explicit touch-resizing; coordinates are display pixels, not video source dimensions. */
    public void adjustSplit(float deltaPixels){
        if(!Float.isFinite(deltaPixels))return;
        if(landscape){
            float usable=Math.max(dp(180),getWidth()-getPaddingLeft()-getPaddingRight()-dp(22));
            landscapeShare=Math.max(.30f,Math.min(.72f,landscapeShare+deltaPixels/usable));
            getContext().getSharedPreferences(SIZING_PREFS,Context.MODE_PRIVATE).edit()
                .putInt("landscapeSharePercent",Math.round(landscapeShare*100)).apply();
            if(viewingPanel!=null){
                LinearLayout.LayoutParams viewport=(LinearLayout.LayoutParams)viewingPanel.getLayoutParams();
                viewport.weight=landscapeShare;viewingPanel.setLayoutParams(viewport);
                LinearLayout.LayoutParams inspector=(LinearLayout.LayoutParams)lowerView.getLayoutParams();
                inspector.weight=1-landscapeShare;lowerView.setLayoutParams(inspector);
            }
        }else{
            preferredPreviewHeight=Math.max(dp(96),Math.min(dp(540),preferredPreviewHeight+Math.round(deltaPixels)));
            getContext().getSharedPreferences(SIZING_PREFS,Context.MODE_PRIVATE).edit()
                .putInt("portraitPreviewDp",Math.round(preferredPreviewHeight/getResources().getDisplayMetrics().density)).apply();
            previewView.getLayoutParams().height=preferredPreviewHeight;
        }
        requestLayout();
    }

    /** A visible 21dp drag handle keeps the edited player alive while panes resize. */
    private final class SplitHandle extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean vertical;
        private float last;
        SplitHandle(Context context){
            super(context);setContentDescription("Drag to resize preview and timeline workspaces");
            setFocusable(true);setClickable(true);
            setMinimumWidth(dp(21));setMinimumHeight(dp(21));
        }
        void setVertical(boolean value){vertical=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            canvas.drawColor(BG);
            paint.setColor(RULE);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            if(vertical){
                for(int i=-1;i<=1;i++)canvas.drawRoundRect(cx-2,cy+i*12-3,cx+2,cy+i*12+3,dp(2),dp(2),paint);
            }else{
                for(int i=-1;i<=1;i++)canvas.drawRoundRect(cx+i*12-3,cy-2,cx+i*12+3,cy+2,dp(2),dp(2),paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    last=vertical?event.getRawX():event.getRawY();
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:{
                    float next=vertical?event.getRawX():event.getRawY();
                    adjustSplit(next-last);last=next;return true;
                }
                case MotionEvent.ACTION_UP:performClick();return true;
                case MotionEvent.ACTION_CANCEL:return true;
                default:return super.onTouchEvent(event);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private int naturalHeight(View view,int width){
        if(view.getVisibility()==GONE)return 0;
        view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    public void showPane(String name){
        if(!java.util.Arrays.asList(PANES).contains(name))throw new IllegalArgumentException("Unknown editor pane");
        if(name.equals(activePane))return;
        View content=renderPane.apply(name);if(content==null)throw new IllegalStateException("Editor pane is unavailable");
        pane.removeAllViews();pane.addView(content,new FrameLayout.LayoutParams(-1,-1));
        activePane=name;tabs.select(name);selected.accept(name);StudioUi.prepareTouchLayout(content);applyQuiet(content);
    }

    /** Refresh selection/values without touching preview, transport or screen generation. */
    public void refreshPane(){String current=activePane;activePane=null;showPane(current==null?"Timeline":current);}
    private int dp(int value){return StudioUi.dp(getContext(),value);}

    /** Palette and native outline icons are scoped to this Editor, never rendered media. */
    public static void applyQuiet(View view){
        if(view instanceof androidx.media3.ui.PlayerView)return;
        if(view instanceof Button)styleQuietButton((Button)view);
        else if(view instanceof SeekBar){
            SeekBar seek=(SeekBar)view;seek.setProgressTintList(ColorStateList.valueOf(SAGE));seek.setThumbTintList(ColorStateList.valueOf(SAGE));seek.setProgressBackgroundTintList(ColorStateList.valueOf(RULE));
        }else if(view instanceof TextView){TextView text=(TextView)view;text.setTextColor("editor-native-status".equals(view.getTag())?("●".contentEquals(text.getText())?SAGE:MUTED):TEXT);}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)applyQuiet(((ViewGroup)view).getChildAt(i));
    }

    public static void styleQuietButton(Button button){
        Object tag=button.getTag();boolean play="editor-play".equals(tag),tab="editor-pane-tab".equals(tag),tool="editor-bottom-tool".equals(tag),icon="editor-icon".equals(tag),export="editor-export".equals(tag);
        int text=play?BG:button.isEnabled()?tab&&!button.isSelected()?MUTED:TEXT:MUTED;
        button.setTextColor(text);button.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        GradientDrawable surface=new GradientDrawable();surface.setColor(play?SAGE:BG);
        surface.setCornerRadius(StudioUi.dp(button.getContext(),play?32:4));
        if(!play&&!tool&&!icon&&!export)surface.setStroke(StudioUi.dp(button.getContext(),1),tab?button.isSelected()?SAGE:BG:RULE);
        button.setBackgroundTintList(null);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x249cae9b),surface,null));
        if(tool){button.setTextSize(12);button.setPadding(StudioUi.dp(button.getContext(),4),StudioUi.dp(button.getContext(),6),StudioUi.dp(button.getContext(),4),StudioUi.dp(button.getContext(),6));button.setCompoundDrawablePadding(StudioUi.dp(button.getContext(),5));}
        else if(play)button.setPadding(StudioUi.dp(button.getContext(),12),StudioUi.dp(button.getContext(),12),StudioUi.dp(button.getContext(),12),StudioUi.dp(button.getContext(),12));
        else if(export)button.setPadding(StudioUi.dp(button.getContext(),6),StudioUi.dp(button.getContext(),10),StudioUi.dp(button.getContext(),6),StudioUi.dp(button.getContext(),10));
    }

    public static Drawable icon(Context context,String kind,boolean dark){return new OutlineIcon(context,kind,dark?BG:TEXT);}

    public static View editingTools(Context context,String[] labels,Runnable[] actions){
        ActionTabs row=new ActionTabs(context);
        String[] icons={"trim","adjust","motion","audio","more"};
        for(int i=0;i<labels.length;i++){
            Button button=new Button(context);button.setText(labels[i]);button.setContentDescription(labels[i]+" selected editing target");
            StudioUi.styleButton(button,false);button.setTag("editor-bottom-tool");button.setSingleLine(true);button.setMaxLines(1);
            button.setCompoundDrawablesWithIntrinsicBounds(null,icon(context,icons[i],false),null,null);styleQuietButton(button);
            Runnable action=actions[i];button.setOnClickListener(view->action.run());row.addView(button,new ViewGroup.LayoutParams(-2,-2));
        }
        return row;
    }

    private static class ActionTabs extends ViewGroup {
        ActionTabs(Context context){super(context);setMinimumHeight(StudioUi.dp(context,64));}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),height=getSuggestedMinimumHeight();
            for(int i=0;i<getChildCount();i++){
                int size=width*(i+1)/getChildCount()-width*i/getChildCount();
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));height=Math.max(height,getChildAt(i).getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){int width=right-left;for(int i=0;i<getChildCount();i++)getChildAt(i).layout(width*i/getChildCount(),0,width*(i+1)/getChildCount(),bottom-top);}
    }

    private static final class OutlineIcon extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final String kind;private final int size;
        OutlineIcon(Context context,String kind,int color){this.kind=kind;size=StudioUi.dp(context,24);paint.setColor(color);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);setBounds(0,0,size,size);}
        @Override public int getIntrinsicWidth(){return size;}@Override public int getIntrinsicHeight(){return size;}
        @Override public void draw(Canvas canvas){int saved=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            switch(kind){
                case "trim":canvas.drawCircle(6,6,3,paint);canvas.drawCircle(6,18,3,paint);canvas.drawLine(8,8,20,20,paint);canvas.drawLine(8,16,20,4,paint);break;
                case "adjust":for(int i=0;i<3;i++){float y=5+i*7,x=i==1?8:15;canvas.drawLine(3,y,21,y,paint);canvas.drawCircle(x,y,2.5f,paint);}break;
                case "motion":canvas.drawRoundRect(3,6,21,20,2,2,paint);canvas.drawCircle(12,13,4,paint);canvas.drawLine(7,6,9,3,paint);canvas.drawLine(9,3,16,3,paint);break;
                case "audio":canvas.drawLine(8,5,20,3,paint);canvas.drawLine(8,5,8,19,paint);canvas.drawLine(20,3,20,16,paint);canvas.drawOval(3,16,8,21,paint);canvas.drawOval(15,13,20,18,paint);break;
                case "back":canvas.drawLine(15,5,8,12,paint);canvas.drawLine(8,12,15,19,paint);break;
                case "previous":canvas.drawLine(6,5,6,19,paint);triangle(canvas,18,5,8,12,18,19);break;
                case "next":canvas.drawLine(18,5,18,19,paint);triangle(canvas,6,5,16,12,6,19);break;
                case "pause":canvas.drawLine(8,6,8,18,paint);canvas.drawLine(16,6,16,18,paint);break;
                case "play":triangle(canvas,8,5,18,12,8,19);break;
                case "export":canvas.drawLine(6,18,19,5,paint);canvas.drawLine(9,5,19,5,paint);canvas.drawLine(19,5,19,15,paint);break;
                default:for(int i=0;i<3;i++)canvas.drawCircle(5+i*7,12,.8f,paint);
            }
            canvas.restoreToCount(saved);
        }
        private void triangle(Canvas canvas,float x1,float y1,float x2,float y2,float x3,float y3){Path path=new Path();path.moveTo(x1,y1);path.lineTo(x2,y2);path.lineTo(x3,y3);path.close();canvas.drawPath(path,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    /** Equal native hit targets; independent of generic button-row font reflow. */
    private static final class PaneTabs extends ViewGroup {
        private final Button[] buttons=new Button[PANES.length];
        PaneTabs(Context context,Consumer<String> choose){
            super(context);setMinimumHeight(StudioUi.dp(context,48));
            for(int i=0;i<buttons.length;i++){
                String name=PANES[i];Button button=new Button(context);button.setText(name);
                button.setContentDescription("Editor "+name+" pane");StudioUi.styleButton(button,false);button.setTag("editor-pane-tab");
                button.setOnClickListener(view->choose.accept(name));buttons[i]=button;
                addView(button,new LayoutParams(-2,-2));
            }
        }
        void select(String name){for(int i=0;i<buttons.length;i++){buttons[i].setSelected(PANES[i].equals(name));styleQuietButton(buttons[i]);}}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),minimum=StudioUi.dp(getContext(),48),height=minimum;
            if(MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED)width=Math.max(minimum*3,getResources().getDisplayMetrics().widthPixels);
            for(int i=0;i<buttons.length;i++){
                int left=width*i/buttons.length,right=width*(i+1)/buttons.length;
                buttons[i].measure(MeasureSpec.makeMeasureSpec(Math.max(minimum,right-left),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                height=Math.max(height,buttons[i].getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            int width=right-left;for(int i=0;i<buttons.length;i++)buttons[i].layout(width*i/buttons.length,0,width*(i+1)/buttons.length,bottom-top);
        }
    }
}
