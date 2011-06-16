package org.apache.zookeeper.test;

import java.io.File;
import java.util.List;

import junit.framework.TestCase;

import org.apache.jute.Record;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;

public class LoadFromLogTest extends TestCase implements  Watcher {
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    private static final int NUM_MESSAGES = 300;


    // setting up the quorum has a transaction overhead for creating and closing the session
    private static final int TRANSACTION_OVERHEAD = 2;	
    private static final int TOTAL_TRANSACTIONS = NUM_MESSAGES + TRANSACTION_OVERHEAD;

   
    public void process(WatchedEvent event) {
        // do nothing
    }

    /**
     * For ZOOKEEPER-1046. Verify if cversion and pzxid if incremented
     * after create/delete failure during restore.
     */
    @Test
    public void testTxnFailure() throws Exception {
        long count = 1;
        File tmpDir = ClientBase.createTmpDir();
        FileTxnSnapLog logFile = new FileTxnSnapLog(tmpDir, tmpDir);
        DataTree dt = new DataTree();
        dt.createNode("/test", new byte[0], null, 0, 1, 1);
        for (count = 1; count <= 3; count++) {
            dt.createNode("/test/" + count, new byte[0], null, 0, count,
                    System.currentTimeMillis());
        }
        DataNode zk = dt.getNode("/test");

        // Make create to fail, then verify cversion.
      
        doOp(logFile, OpCode.create, "/test/" + (count - 1), dt, zk);

        // Make delete fo fail, then verify cversion.
     
        doOp(logFile, OpCode.delete, "/test/" + (count + 1), dt, zk);
    }
    /*
     * Does create/delete depending on the type and verifies
     * if cversion before the operation is 1 less than cversion afer.
     */
    private void doOp(FileTxnSnapLog logFile, int type, String path,
            DataTree dt, DataNode parent) throws Exception {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);

        long prevCversion = parent.stat.getCversion();
        long prevPzxid = parent.stat.getPzxid();
        List<String> child = dt.getChildren(parentName, null, null);
        String childStr = "";
        for (String s : child) {
            childStr += s + " ";
        }
  
        Record txn = null;
        TxnHeader txnHeader = null;
        if (type == OpCode.delete) {
            txn = new DeleteTxn(path);
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                System.currentTimeMillis(), OpCode.delete);
        } else if (type == OpCode.create) {
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                    System.currentTimeMillis(), OpCode.create);
            txn = new CreateTxn(path, new byte[0], null, false);
        }
        logFile.processTransaction(txnHeader, dt, null, txn);

        long newCversion = parent.stat.getCversion();
        long newPzxid = parent.stat.getPzxid();
        child = dt.getChildren(parentName, null, null);
        childStr = "";
        for (String s : child) {
            childStr += s + " ";
        }
        Assert.assertTrue("<cversion, pzxid> verification failed. Expected: <" +
                (prevCversion + 1) + ", " + (prevPzxid + 1) + ">, found: <" +
                newCversion + ", " + newPzxid + ">",
                (newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1));
    }
}
