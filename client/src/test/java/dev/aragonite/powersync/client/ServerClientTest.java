package dev.aragonite.powersync.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServerClientTest {
    @Test public void boundedErrorPrefixLeavesTrailingBytesUnread() throws Exception {
        byte[] bytes=new byte[5000];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        ByteArrayInputStream in=new ByteArrayInputStream(bytes);
        assertArrayEquals(java.util.Arrays.copyOf(bytes,4096),ServerClient.readPrefix(in,4096));
        assertEquals(904,in.available());assertEquals(bytes[4096]&255,in.read());
    }
    @Test public void shortIdentityErrorAndEmptyStreamArePreserved() throws Exception {
        byte[] error="{\"error\":\"native_identity_required\"}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(error,ServerClient.readPrefix(new ByteArrayInputStream(error),4096));
        assertEquals(0,ServerClient.readPrefix(new ByteArrayInputStream(new byte[0]),4096).length);
    }
    @Test public void shortAndZeroLengthReadsCannotStall() throws Exception {
        InputStream in=new ByteArrayInputStream(new byte[]{1,2,3}) {
            boolean first=true;
            @Override public synchronized int read(byte[] b,int off,int len) {
                if(first){first=false;return 0;}return super.read(b,off,Math.min(1,len));
            }
        };
        assertArrayEquals(new byte[]{1,2,3},ServerClient.readPrefix(in,4096));
    }
    @Test public void zeroLimitDoesNotReadAndFailuresPropagate() throws Exception {
        InputStream failure=new InputStream(){public int read()throws IOException{throw new IOException("synthetic");}};
        assertEquals(0,ServerClient.readPrefix(failure,0).length);
        try{ServerClient.readPrefix(failure,1);fail();}catch(IOException expected){assertEquals("synthetic",expected.getMessage());}
    }
}
