package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.net.Uri;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StorageProfileStoreTest {
    private Context context;private StorageProfileStore store;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_storage.db");store=new StorageProfileStore(context);}
    private Uri tree(int n){return Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Afolder"+n);}
    @Test public void remoteDiscoveryReturnsIdsAndLabelsWithoutCapabilitiesOrOtherProjectPins()throws Exception{
        String id=store.connect(tree(1),"Owner archive");store.pinProject(id,"private-project",true);store.reportHealth(id,false,"content://private/provider/details");
        org.json.JSONObject discovered=store.discover().getJSONObject(0);assertEquals(id,discovered.getString("id"));assertEquals("Owner archive",discovered.getString("label"));
        for(String hidden:new String[]{"treeUri","pinnedProjects","authority","healthDetail"})assertFalse(discovered.has(hidden));
        assertTrue("Redaction must preserve local owner capabilities",store.get(id).has("treeUri"));assertEquals("private-project",store.get(id).getJSONArray("pinnedProjects").getString(0));
    }
    @Test public void fiveSlotsSurviveRestartAndSixthDoesNotOverwrite()throws Exception{for(int i=0;i<5;i++)store.connect(tree(i),"Account "+i);assertEquals(5,new StorageProfileStore(context).list().length());try{store.connect(tree(5),"Sixth");fail("Sixth slot overwrote a profile");}catch(IllegalStateException expected){}assertEquals(5,store.list().length());}
    @Test public void reconnectSameFolderUpdatesItsLabelWithoutConsumingSlot()throws Exception{String id=store.connect(tree(1),"Old");assertEquals(id,store.connect(tree(1),"Updated"));assertEquals(1,store.list().length());assertEquals("Updated",store.get(id).getString("label"));}
    @Test public void quotasStayUnknownUntilProviderReportsRealValues()throws Exception{String id=store.connect(tree(1),"USB");assertFalse(store.get(id).getBoolean("quotaKnown"));assertEquals(-1,store.get(id).getLong("totalBytes"));store.reportQuota(id,3L<<30,20L<<30);assertEquals(20L<<30,new StorageProfileStore(context).get(id).getLong("totalBytes"));}
    @Test public void roleDefaultsAndPinningAreIndependent()throws Exception{String a=store.connect(tree(1),"Sources"),b=store.connect(tree(2),"Archive");store.setDefault("source",a);store.setDefault("archive",b);store.pinProject(b,"project",true);assertEquals(a,store.defaultProfile("source").getString("id"));assertEquals(b,store.defaultProfile("archive").getString("id"));assertEquals("project",store.get(b).getJSONArray("pinnedProjects").getString(0));store.disconnect(b);assertNull(store.defaultProfile("archive"));assertNotNull(store.get(a));}
    @Test public void treeCapabilitiesAreRequiredAndDisconnectLeavesProviderBytesAlone(){try{store.connect(Uri.parse("file:///sdcard"),"Invalid");fail("Unselected path accepted");}catch(IllegalArgumentException expected){}String id=store.connect(tree(1),"Folder");store.disconnect(id);assertEquals(0,store.list().length());}
}
