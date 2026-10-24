package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.view.MotionEvent;
import android.graphics.RectF;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Touch contract; only exact committed gestures mutate a saved project. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioResponsiveEditorTest {

    @Test public void fittedCanvasPreservesVerticalAspectInHorizontalOrVerticalViewport(){
        RectF portrait=StudioTouchTransformOverlay.fittedCanvas(300,600,9f/16f);
        assertEquals(300f,portrait.width(),.1f);
        assertEquals(533.333f,portrait.height(),.3f);
        RectF landscape=StudioTouchTransformOverlay.fittedCanvas(700,300,9f/16f);
        assertEquals(168.75f,landscape.width(),.1f);
        assertEquals(300f,landscape.height(),.1f);
        assertTrue(landscape.left>0);
        assertThrows(IllegalArgumentException.class,()->
                StudioTouchTransformOverlay.fittedCanvas(0,400,9f/16f));
    }

    @Test public void draggingOverlayCommitsOneProjectSafeTransform()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        StudioLayer layer=new StudioLayer();layer.type=StudioLayer.SHAPE;
        layer.shapeKind="rect";layer.width=.3f;layer.height=.2f;
        AtomicInteger saved=new AtomicInteger(),canceled=new AtomicInteger();
        final StudioLayer[] update=new StudioLayer[1];
        StudioTouchTransformOverlay overlay=new StudioTouchTransformOverlay(context,layer,
                new StudioTouchTransformOverlay.Listener(){
                    public void onCommit(StudioLayer candidate){saved.incrementAndGet();update[0]=candidate;}
                    public void onCancel(){canceled.incrementAndGet();}
                });
        FrameLayout parent=new FrameLayout(context);
        parent.addView(overlay,new FrameLayout.LayoutParams(300,600));
        parent.layout(0,0,300,600);overlay.layout(0,0,300,600);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,150,300,0);
        MotionEvent move=MotionEvent.obtain(0,20,MotionEvent.ACTION_MOVE,180,330,0);
        MotionEvent up=MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,180,330,0);
        try{
            assertTrue(overlay.onTouchEvent(down));
            assertTrue(overlay.onTouchEvent(move));
            assertTrue(overlay.onTouchEvent(up));
            assertEquals(1,saved.get());assertEquals(0,canceled.get());
            assertNotNull(update[0]);
            assertTrue(update[0].x>layer.x);
            assertTrue(update[0].y>layer.y);
            assertEquals("The source layer itself is not modified before parent save",.5f,layer.x,0);
        }finally{down.recycle();move.recycle();up.recycle();}
    }

    @Test public void tappingWithoutMovementNeverCreatesUnnecessaryProjectRevision(){
        Context context=RuntimeEnvironment.getApplication();
        StudioLayer layer=new StudioLayer();layer.type=StudioLayer.SHAPE;layer.shapeKind="ring";
        AtomicInteger writes=new AtomicInteger(),cancels=new AtomicInteger();
        StudioTouchTransformOverlay overlay=new StudioTouchTransformOverlay(context,layer,
            new StudioTouchTransformOverlay.Listener(){
                public void onCommit(StudioLayer c){writes.incrementAndGet();}
                public void onCancel(){cancels.incrementAndGet();}
            });
        overlay.layout(0,0,300,600);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,150,300,0);
        MotionEvent up=MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,150,300,0);
        try{
            assertTrue(overlay.onTouchEvent(down));assertTrue(overlay.onTouchEvent(up));
            assertEquals(0,writes.get());assertEquals(1,cancels.get());
        }finally{down.recycle();up.recycle();}
    }

    @Test public void previewTimelineSplitAdjustmentsStayBoundedAndPreserveHierarchy(){
        Context context=RuntimeEnvironment.getApplication();
        View header=new View(context),preview=new View(context),transport=new View(context),tools=new View(context);
        StudioEditorWorkspace workspace=new StudioEditorWorkspace(context,header,preview,transport,
                tools,"Timeline",pane->new View(context),selected->{});
        assertNotNull(preview.getParent());
        int before=workspace.portraitPreviewHeightPx();
        workspace.adjustSplit(1000);
        assertTrue(workspace.portraitPreviewHeightPx()>=before);
        assertTrue(workspace.portraitPreviewHeightPx()<=
                Math.round(context.getResources().getDisplayMetrics().density*540));
        workspace.adjustSplit(-5000);
        assertTrue(workspace.portraitPreviewHeightPx()>=
                Math.round(context.getResources().getDisplayMetrics().density*96));
        assertNotNull(preview.getParent());
        assertNotNull(transport.getParent());
    }
}
