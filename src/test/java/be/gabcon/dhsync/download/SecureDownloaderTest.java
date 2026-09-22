package be.gabcon.dhsync.download;

import be.gabcon.dhsync.util.Hashes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SecureDownloaderTest {
    @TempDir Path temp;
    private HttpServer server;
    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void resumesPartialDownloadWithRange() throws Exception {
        byte[] data = new byte[128 * 1024]; for (int i=0;i<data.length;i++) data[i]=(byte)(i*31);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/asset",e->serveRangeAware(e,data)); server.start();
        String fileName="asset.gcdh"; Path part=temp.resolve(fileName+".part"); Files.write(part,Arrays.copyOf(data,4096));
        try (var executor=Executors.newFixedThreadPool(2)) {
            Path result=new SecureDownloader(executor).downloadBlocking(request(fileName,data.length,sha(data)),ignored->{});
            assertArrayEquals(data,Files.readAllBytes(result)); assertFalse(Files.exists(part));
        }
    }

    @Test void rejectsBadHashAndDeletesPart() throws Exception {
        byte[] data="real payload".getBytes(); server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/asset",e->send(e,200,data)); server.start();
        try (var executor=Executors.newSingleThreadExecutor()) {
            DownloadRequest request=request("bad.gcdh",data.length,"0".repeat(64));
            assertThrows(IOException.class,()->new SecureDownloader(executor).downloadBlocking(request,ignored->{})); assertFalse(Files.exists(temp.resolve("bad.gcdh.part")));
        }
    }

    @Test void incompleteDownloadLeavesPartForResume() throws Exception {
        byte[] full=new byte[1000], shortBody=new byte[400]; server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/asset",e->send(e,200,shortBody)); server.start();
        try (var executor=Executors.newSingleThreadExecutor()) {
            DownloadRequest request=request("short.gcdh",full.length,sha(full));
            assertThrows(IOException.class,()->new SecureDownloader(executor).downloadBlocking(request,ignored->{})); assertTrue(Files.exists(temp.resolve("short.gcdh.part")));
        }
    }

    private DownloadRequest request(String fileName,long size,String hash){ return new DownloadRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/asset"),temp,fileName,size,hash,2_000_000,true); }
    private static String sha(byte[] data) throws Exception { Path p=Files.createTempFile("hash",".bin"); try { Files.write(p,data); return Hashes.sha256(p);} finally {Files.deleteIfExists(p);} }
    private static void serveRangeAware(HttpExchange exchange,byte[] data)throws IOException { String range=exchange.getRequestHeaders().getFirst("Range"); if(range!=null&&range.startsWith("bytes=")){int start=Integer.parseInt(range.substring("bytes=".length(),range.length()-1)); byte[] body=Arrays.copyOfRange(data,start,data.length); exchange.getResponseHeaders().set("Content-Range","bytes "+start+"-"+(data.length-1)+"/"+data.length); send(exchange,206,body);} else send(exchange,200,data); }
    private static void send(HttpExchange exchange,int status,byte[] body)throws IOException { exchange.sendResponseHeaders(status,body.length); try(var out=exchange.getResponseBody()){out.write(body);} }
}
