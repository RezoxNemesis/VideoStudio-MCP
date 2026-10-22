package com.rezoxnemesis.videostudio;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RenderSerialQueueTest {
    @Test public void onlyOneTaskRunsUntilItsReceipt(){
        List<Runnable> scheduled=new ArrayList<>();List<Integer> executed=new ArrayList<>();
        RenderSerialQueue q=new RenderSerialQueue(3,scheduled::add);
        assertTrue(q.submit(()->executed.add(1)));assertTrue(q.submit(()->executed.add(2)));assertTrue(q.submit(()->executed.add(3)));
        assertEquals(1,scheduled.size());assertEquals(3,q.size());scheduled.get(0).run();assertEquals(java.util.Arrays.asList(1),executed);
        q.completed();assertEquals(2,scheduled.size());scheduled.get(1).run();q.completed();scheduled.get(2).run();q.completed();
        assertEquals(java.util.Arrays.asList(1,2,3),executed);assertEquals(0,q.size());
    }
    @Test public void rejectsOverflowWithoutDroppingAcceptedWork(){
        List<Runnable> scheduled=new ArrayList<>();RenderSerialQueue q=new RenderSerialQueue(2,scheduled::add);
        assertTrue(q.submit(()->{}));assertTrue(q.submit(()->{}));assertFalse(q.submit(()->{}));assertFalse(q.submit(null));assertEquals(2,q.size());
        q.completed();assertTrue(q.submit(()->{}));assertEquals(2,q.size());
    }
    @Test public void emptyCompletionDoesNotScheduleGhostWork(){
        List<Runnable> scheduled=new ArrayList<>();RenderSerialQueue q=new RenderSerialQueue(1,scheduled::add);
        q.completed();assertEquals(0,scheduled.size());assertEquals(0,q.size());
    }
}
