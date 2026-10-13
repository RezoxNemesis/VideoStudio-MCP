package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Shared native surfaces; dimensions grow with labels and system font settings. */
public final class StudioUi {
    public static final int BG=0xff111318, SURFACE=0xff1b1e25, SURFACE_ALT=0xff242832;
    public static final int TEXT=0xfff5f5f1, MUTED=0xff9ba3af, ACCENT=0xffa7f3d0, BORDER=0xff343944;
    private StudioUi() {}
    public static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    public static void styleButton(Button button,boolean primary){
        Context context=button.getContext();
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{MUTED,primary?BG:TEXT}));
        button.setMinHeight(dp(context,48));
        button.setMinimumHeight(dp(context,48));
        button.setMinWidth(dp(context,48));
        button.setMinimumWidth(dp(context,48));
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
        button.setPadding(dp(context,12),dp(context,10),dp(context,12),dp(context,10));
        GradientDrawable surface=new GradientDrawable();surface.setColor(primary?ACCENT:SURFACE_ALT);
        surface.setCornerRadius(dp(context,12));surface.setStroke(dp(context,1),primary?ACCENT:BORDER);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x309ba3af),surface,null));
    }

    /** Existing native screens used fixed short rows; retain actions while allowing text to grow. */
    public static void prepareTouchLayout(View view){
        if(view instanceof Button){
            ViewGroup.LayoutParams params=view.getLayoutParams();
            int minimumTouch=dp(view.getContext(),48);
            if(params!=null){
                params.height=ViewGroup.LayoutParams.WRAP_CONTENT;
                // Exact widths override a Button's minimum during Android measurement.
                if(params.width>0&&params.width<minimumTouch)params.width=minimumTouch;
            }
            view.setMinimumHeight(minimumTouch);
            view.setMinimumWidth(minimumTouch);
        }
        if(!(view instanceof ViewGroup))return;
        ViewGroup group=(ViewGroup)view;
        boolean directButton=false,buttonRow=group.getChildCount()>0;
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            directButton|=child instanceof Button;
            buttonRow&=child instanceof Button||child.getClass()==View.class;
        }
        if(directButton){
            ViewGroup.LayoutParams params=view.getLayoutParams();
            if(params!=null&&params.height>0)params.height=ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        boolean compact=view.getResources().getConfiguration().screenWidthDp<360
                ||view.getResources().getConfiguration().fontScale>1.2f;
        if(compact&&buttonRow&&group instanceof LinearLayout&&!(group.getParent() instanceof HorizontalScrollView)){
            ((LinearLayout)group).setOrientation(LinearLayout.VERTICAL);
            for(int i=0;i<group.getChildCount();i++){
                View child=group.getChildAt(i);
                if(child.getClass()==View.class){child.setVisibility(View.GONE);continue;}
                LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)child.getLayoutParams();
                params.width=ViewGroup.LayoutParams.MATCH_PARENT;params.height=ViewGroup.LayoutParams.WRAP_CONTENT;params.weight=0;
                params.bottomMargin=dp(view.getContext(),4);
            }
        }
        for(int i=0;i<group.getChildCount();i++)prepareTouchLayout(group.getChildAt(i));
    }
}
