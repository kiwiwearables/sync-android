/**
 * Copyright (c) 2014 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.replication;

import com.cloudant.common.RequireRunningCouchDB;
import com.cloudant.sync.datastore.Attachment;
import com.cloudant.sync.datastore.ConflictException;
import com.cloudant.sync.datastore.DocumentRevision;
import com.cloudant.sync.datastore.UnsavedFileAttachment;
import com.cloudant.sync.util.TestUtils;
import com.cloudant.sync.util.TypedDatastore;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.Is.isA;

/**
 * Created by tomblench on 26/03/2014.
 */

@Category(RequireRunningCouchDB.class)
public class AttachmentsPushTest extends ReplicationTestBase {

    String id1;
    String id2;
    String id3;

    private TypedDatastore<Foo> fooTypedDatastore;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        this.fooTypedDatastore = new TypedDatastore<Foo>(
                Foo.class,
                this.datastore
        );
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }


    /**
     * After all documents are created:
     *
     * Doc 1: 1 -> 2 -> 3
     * Doc 2: 1 -> 2
     * Doc 3: 1 -> 2 -> 3
     */
    private void populateSomeDataInLocalDatastore() throws ConflictException {

        id1 = createDocInDatastore("Doc 1");
        Assert.assertNotNull(id1);
        updateDocInDatastore(id1, "Doc 1");
        updateDocInDatastore(id1, "Doc 1");

        id2 = createDocInDatastore("Doc 2");
        Assert.assertNotNull(id2);
        updateDocInDatastore(id2, "Doc 2");

        id3 = createDocInDatastore("Doc 3");
        Assert.assertNotNull(id3);
        updateDocInDatastore(id3, "Doc 3");
        updateDocInDatastore(id3, "Doc 3");
    }

    public String createDocInDatastore(String d) throws ConflictException {
        Foo foo = new Foo();
        foo.setFoo(d + " (from local)");
        foo = this.fooTypedDatastore.createDocument(foo);
        return foo.getId();
    }


    public String updateDocInDatastore(String id, String data) throws ConflictException {
        Foo foo = this.fooTypedDatastore.getDocument(id);
        foo.setFoo(data + " (from local)");
        foo = this.fooTypedDatastore.updateDocument(foo);
        return foo.getId();
    }

    @Test
    public void pushAttachmentsTest() throws Exception {
        // simple 1-rev attachment
        String attachmentName = "attachment_1.txt";
        populateSomeDataInLocalDatastore();
        File f = new File("fixture", attachmentName);
        Attachment att = new UnsavedFileAttachment(f, "text/plain");
        List<Attachment> atts = new ArrayList<Attachment>();
        atts.add(att);
        DocumentRevision oldRevision = datastore.getDocument(id1);
        DocumentRevision newRevision = null;
        try {
            // set attachment
            newRevision = datastore.updateAttachments(oldRevision, atts);
        } catch (IOException ioe) {
            Assert.fail("IOException thrown: " + ioe);
        }

        // push replication
        push();

        // check it's in the DB
        InputStream is1 = this.couchClient.getAttachmentStream(id1, attachmentName);
        InputStream is2 = new FileInputStream(f);
        Assert.assertTrue("Attachment not the same", TestUtils.streamsEqual(is1, is2));
    }

    @Test
    public void pushAttachmentsTest2() throws Exception {
        // more complex test with attachments changing between revisions
        String attachmentName1 = "attachment_1.txt";
        String attachmentName2 = "attachment_2.txt";
        populateSomeDataInLocalDatastore();
        File f1 = new File("fixture", attachmentName1);
        File f2 = new File("fixture", attachmentName2);
        Attachment att1 = new UnsavedFileAttachment(f1, "text/plain");
        Attachment att2 = new UnsavedFileAttachment(f2, "text/plain");
        List<Attachment> atts1 = new ArrayList<Attachment>();
        atts1.add(att1);
        List<Attachment> atts2 = new ArrayList<Attachment>();
        atts2.add(att2);
        DocumentRevision rev1 = datastore.getDocument(id1);
        DocumentRevision rev2 = null;
        try {
            // set attachment
            rev2 = datastore.updateAttachments(rev1, atts1);
        } catch (IOException ioe) {
            Assert.fail("IOException thrown: "+ioe);
        }

        // push replication - att1 should be uploaded
        push();

        DocumentRevision rev3 = datastore.updateDocument(rev2.getId(), rev2.getRevision(), rev2.getBody());

        // push replication - no atts should be uploaded
        push();

        DocumentRevision rev4 = null;
        try {
            // set attachment
            rev4 = datastore.updateAttachments(rev3, atts2);
        } catch (IOException ioe) {
            Assert.fail("IOException thrown: "+ioe);
        }
        // push replication - att2 should be uploaded
        push();

        InputStream isOriginal1;
        InputStream isOriginal2;

        InputStream isRev2 = this.couchClient.getAttachmentStream(id1, rev2.getRevision(), attachmentName1);
        InputStream isRev3 = this.couchClient.getAttachmentStream(id1, rev3.getRevision(), attachmentName1);
        InputStream isRev4Att1 = this.couchClient.getAttachmentStream(id1, rev4.getRevision(), attachmentName1);
        InputStream isRev4Att2 = this.couchClient.getAttachmentStream(id1, rev4.getRevision(), attachmentName2);

        isOriginal1 = new FileInputStream(f1);
        Assert.assertTrue("Attachment not the same", TestUtils.streamsEqual(isRev2, isOriginal1));

        isOriginal1 = new FileInputStream(f1);
        Assert.assertTrue("Attachment not the same", TestUtils.streamsEqual(isRev3, isOriginal1));

        isOriginal1 = new FileInputStream(f1);
        isOriginal2 = new FileInputStream(f2);
        Assert.assertTrue("Attachment not the same", TestUtils.streamsEqual(isRev4Att1, isOriginal1));
        Assert.assertTrue("Attachment not the same", TestUtils.streamsEqual(isRev4Att2, isOriginal2));

    }

    @Test
    @Ignore
    public void pushAttachmentsTestMissingStub() throws Exception {
        // more complex test with attachments changing between revisions
        String attachmentName1 = "attachment_1.txt";
        String attachmentName2 = "attachment_2.txt";
        populateSomeDataInLocalDatastore();
        File f1 = new File("fixture", attachmentName1);
        File f2 = new File("fixture", attachmentName2);
        Attachment att1 = new UnsavedFileAttachment(f1, "text/plain");
        Attachment att2 = new UnsavedFileAttachment(f2, "text/plain");
        List<Attachment> atts1 = new ArrayList<Attachment>();
        atts1.add(att1);
        atts1.add(att2);
        DocumentRevision rev1 = datastore.getDocument(id1);
        DocumentRevision rev2 = null;
        try {
            // set attachment
            rev2 = datastore.updateAttachments(rev1, atts1);
        } catch (IOException ioe) {
            Assert.fail("IOException thrown: "+ioe);
        }

        // push replication - att1 should be uploaded
        push();

        // remove attachment
        DocumentRevision rev3 = datastore.removeAttachments(rev2, new String[]{attachmentName1});

        // change revpos
        this.database.execSQL(String.format("UPDATE attachments SET revpos=1 WHERE filename='%s'", attachmentName2));
        // push replication - currently fails with missing_stub CouchException
        push();

        // TODO check attachments
    }

    private void push() throws Exception {
        TestStrategyListener listener = new TestStrategyListener();
        BasicPushStrategy push = new BasicPushStrategy(this.createPushReplication());
        push.eventBus.register(listener);

        Thread t = new Thread(push);
        t.start();
        t.join();
        Assert.assertTrue(listener.finishCalled);
        Assert.assertFalse(listener.errorCalled);
    }






}
