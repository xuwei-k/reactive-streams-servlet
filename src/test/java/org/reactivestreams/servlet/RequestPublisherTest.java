/************************************************************************
 * Licensed under Public Domain (CC0)                                    *
 *                                                                       *
 * To the extent possible under law, the person who associated CC0 with  *
 * this code has waived all copyright and related or neighboring         *
 * rights to this code.                                                  *
 *                                                                       *
 * You should have received a copy of the CC0 legalcode along with this  *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.*
 ************************************************************************/
package org.reactivestreams.servlet;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class RequestPublisherTest extends PublisherVerification<ByteBuffer> {

  private Server server;
  private HttpClient client;
  private List<AsyncContext> requests;
  private int port;
  private volatile CompletableFuture<Publisher<ByteBuffer>> nextPublisher;

  public RequestPublisherTest() {
    super(ServletTestEnvironment.INSTANCE);
  }

  @BeforeClass
  public void start() throws Exception {
    server = new Server(0);
    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (nextPublisher == null) {
          response.sendError(500, "No next publisher");
        } else {
          AsyncContext context = request.startAsync();
          requests.add(context);
          // Read buffer limit must be 1, because there is no way to guarantee the number of elements that will be read
          // if we read more than one byte in each read.
          nextPublisher.complete(new RequestPublisher(context, 1));
        }
      }
    });
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @AfterClass
  public void stop() throws Exception {
    server.stop();
  }

  @BeforeMethod
  public void before() throws Exception {
    requests = new CopyOnWriteArrayList<>();
    client = new HttpClient();
    client.setMaxConnectionsPerDestination(1000);
    client.start();
  }

  @AfterMethod
  public void after() throws Exception {
    requests.forEach(AsyncContext::complete);
    client.stop();
  }

  @Override
  public Publisher<ByteBuffer> createPublisher(long elements) {

    try {
      nextPublisher = new CompletableFuture<>();

      client.POST("http://localhost:" + port)
          .header("Connection", "close")
          .content(new ContentProvider() {
            @Override
            public long getLength() {
              return -1;
            }

            @Override
            public Iterator<ByteBuffer> iterator() {
              return new Iterator<ByteBuffer>() {
                volatile long count = 0;
                @Override
                public boolean hasNext() {
                  return count < elements;
                }

                @Override
                public ByteBuffer next() {
                  count += 1;
                  if (elements >= Integer.MAX_VALUE) {
                    return ByteBuffer.wrap(new byte[8192]);
                  } else {
                    return ByteBuffer.wrap(new byte[] {(byte) count});
                  }
                }
              };
            }
          }).send(result -> {});

      Publisher<ByteBuffer> publisher = nextPublisher.get(1, TimeUnit.SECONDS);
      nextPublisher = null;
      return publisher;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Publisher<ByteBuffer> createFailedPublisher() {
    return null;
  }
}
