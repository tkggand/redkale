/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import static org.redkale.net.ProtocolServer.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public abstract class AsyncConnection implements AsynchronousByteChannel, AutoCloseable {

    protected SSLContext sslContext;

    protected Map<String, Object> attributes; //用于存储绑定在Connection上的对象集合

    protected Object subobject; //用于存储绑定在Connection上的对象， 同attributes， 只绑定单个对象时尽量使用subobject而非attributes

    protected volatile long readtime;

    protected volatile long writetime;

    //在线数
    protected AtomicLong livingCounter;

    //关闭数
    protected AtomicLong closedCounter;

    protected Consumer<AsyncConnection> beforeCloseListener;

    public final long getLastReadTime() {
        return readtime;
    }

    public final long getLastWriteTime() {
        return writetime;
    }

    public abstract boolean isTCP();

    public abstract SocketAddress getRemoteAddress();

    public abstract SocketAddress getLocalAddress();

    public abstract int getReadTimeoutSeconds();

    public abstract int getWriteTimeoutSeconds();

    public abstract void setReadTimeoutSeconds(int readTimeoutSeconds);

    public abstract void setWriteTimeoutSeconds(int writeTimeoutSeconds);

    @Override
    public abstract Future<Integer> read(ByteBuffer dst);

    @Override
    public abstract <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler);

    public abstract <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler);

    @Override
    public abstract Future<Integer> write(ByteBuffer src);

    @Override
    public abstract <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler);

    public final <A> void write(ByteBuffer[] srcs, A attachment, CompletionHandler<Integer, ? super A> handler) {
        write(srcs, 0, srcs.length, attachment, handler);
    }

    public abstract <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler);

    public void dispose() {//同close， 只是去掉throws IOException
        try {
            this.close();
        } catch (IOException io) {
        }
    }

    public AsyncConnection beforeCloseListener(Consumer<AsyncConnection> beforeCloseListener) {
        this.beforeCloseListener = beforeCloseListener;
        return this;
    }

    @Override
    public void close() throws IOException {
        if (closedCounter != null) {
            closedCounter.incrementAndGet();
            closedCounter = null;
        }
        if (livingCounter != null) {
            livingCounter.decrementAndGet();
            livingCounter = null;
        }
        if (beforeCloseListener != null) beforeCloseListener.accept(this);
        if (attributes == null) return;
        try {
            for (Object obj : attributes.values()) {
                if (obj instanceof AutoCloseable) ((AutoCloseable) obj).close();
            }
        } catch (Exception io) {
        }
    }

    @SuppressWarnings("unchecked")
    public final <T> T getSubobject() {
        return (T) this.subobject;
    }

    public void setSubobject(Object value) {
        this.subobject = value;
    }

    public void setAttribute(String name, Object value) {
        if (this.attributes == null) this.attributes = new HashMap<>();
        this.attributes.put(name, value);
    }

    @SuppressWarnings("unchecked")
    public final <T> T getAttribute(String name) {
        return (T) (this.attributes == null ? null : this.attributes.get(name));
    }

    public final void removeAttribute(String name) {
        if (this.attributes != null) this.attributes.remove(name);
    }

    public final Map<String, Object> getAttributes() {
        return this.attributes;
    }

    public final void clearAttribute() {
        if (this.attributes != null) this.attributes.clear();
    }

    //------------------------------------------------------------------------------------------------------------------------------
    /**
     * 创建TCP协议客户端连接
     *
     * @param address             连接点子
     * @param group               连接AsynchronousChannelGroup
     * @param readTimeoutSeconds  读取超时秒数
     * @param writeTimeoutSeconds 写入超时秒数
     *
     * @return 连接CompletableFuture
     */
    public static CompletableFuture<AsyncConnection> createTCP(final AsynchronousChannelGroup group, final SocketAddress address,
        final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        return createTCP(group, null, address, supportTcpNoDelay(), readTimeoutSeconds, writeTimeoutSeconds);
    }

    /**
     * 创建TCP协议客户端连接
     *
     * @param address             连接点子
     * @param sslContext          SSLContext
     * @param group               连接AsynchronousChannelGroup
     * @param readTimeoutSeconds  读取超时秒数
     * @param writeTimeoutSeconds 写入超时秒数
     *
     * @return 连接CompletableFuture
     */
    public static CompletableFuture<AsyncConnection> createTCP(final AsynchronousChannelGroup group, final SSLContext sslContext,
        final SocketAddress address, final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        return createTCP(group, sslContext, address, false, readTimeoutSeconds, writeTimeoutSeconds);
    }

    /**
     * 创建TCP协议客户端连接
     *
     * @param address             连接点子
     * @param sslContext          SSLContext
     * @param group               连接AsynchronousChannelGroup
     * @param noDelay             TcpNoDelay
     * @param readTimeoutSeconds  读取超时秒数
     * @param writeTimeoutSeconds 写入超时秒数
     *
     * @return 连接CompletableFuture
     */
    public static CompletableFuture<AsyncConnection> createTCP(final AsynchronousChannelGroup group, final SSLContext sslContext,
        final SocketAddress address, final boolean noDelay, final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        final CompletableFuture<AsyncConnection> future = new CompletableFuture<>();
        try {
            final AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(group);
            try {
                if (noDelay) channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                if (supportTcpKeepAlive()) channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            } catch (IOException e) {
            }
            channel.connect(address, null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    future.complete(create(channel, sslContext, address, readTimeoutSeconds, writeTimeoutSeconds));
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    future.completeExceptionally(exc);
                }
            });
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static class NIOTCPAsyncConnection extends AsyncConnection {

        private int readTimeoutSeconds;

        private int writeTimeoutSeconds;

        private final SocketChannel channel;

        private final SocketAddress remoteAddress;

        public NIOTCPAsyncConnection(final SocketChannel ch, SocketAddress addr,
            final int readTimeoutSeconds0, final int writeTimeoutSeconds0,
            final AtomicLong livingCounter, final AtomicLong closedCounter) {
            this.channel = ch;
            this.readTimeoutSeconds = readTimeoutSeconds0;
            this.writeTimeoutSeconds = writeTimeoutSeconds0;
            this.remoteAddress = addr;
            this.livingCounter = livingCounter;
            this.closedCounter = closedCounter;
        }

        @Override
        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        @Override
        public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
        }

        @Override
        public int getReadTimeoutSeconds() {
            return this.readTimeoutSeconds;
        }

        @Override
        public int getWriteTimeoutSeconds() {
            return this.writeTimeoutSeconds;
        }

        @Override
        public final SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return channel.getLocalAddress();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = (int) channel.write(srcs, offset, length);
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (Exception e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.read(dst);
                this.readtime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            read(dst, attachment, handler);
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            try {
                int rs = channel.read(dst);
                this.readtime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.write(src);
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            try {
                int rs = channel.read(src);
                this.writetime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public final void close() throws IOException {
            super.close();
            channel.close();
        }

        @Override
        public final boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public final boolean isTCP() {
            return true;
        }
    }

    public static AsyncConnection create(final SocketChannel ch, SocketAddress addr,
        final int readTimeoutSeconds0, final int writeTimeoutSeconds0) {
        return new NIOTCPAsyncConnection(ch, addr, readTimeoutSeconds0, writeTimeoutSeconds0, null, null);
    }

    public static AsyncConnection create(final SocketChannel ch, SocketAddress addr,
        final int readTimeoutSeconds0, final int writeTimeoutSeconds0,
        final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new NIOTCPAsyncConnection(ch, addr, readTimeoutSeconds0, writeTimeoutSeconds0, livingCounter, closedCounter);
    }

    private static class BIOUDPAsyncConnection extends AsyncConnection {

        private int readTimeoutSeconds;

        private int writeTimeoutSeconds;

        private final DatagramChannel channel;

        private final SocketAddress remoteAddress;

        private final boolean client;

        public BIOUDPAsyncConnection(final DatagramChannel ch, SocketAddress addr,
            final boolean client0, final int readTimeoutSeconds0, final int writeTimeoutSeconds0,
            final AtomicLong livingCounter, final AtomicLong closedCounter) {
            this.channel = ch;
            this.client = client0;
            this.readTimeoutSeconds = readTimeoutSeconds0;
            this.writeTimeoutSeconds = writeTimeoutSeconds0;
            this.remoteAddress = addr;
            this.livingCounter = livingCounter;
            this.closedCounter = closedCounter;
        }

        @Override
        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        @Override
        public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
        }

        @Override
        public int getReadTimeoutSeconds() {
            return this.readTimeoutSeconds;
        }

        @Override
        public int getWriteTimeoutSeconds() {
            return this.writeTimeoutSeconds;
        }

        @Override
        public final SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return channel.getLocalAddress();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = 0;
                for (int i = offset; i < offset + length; i++) {
                    rs += channel.send(srcs[i], remoteAddress);
                    if (i != offset) Thread.sleep(10);
                }
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (Exception e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.read(dst);
                this.readtime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            read(dst, attachment, handler);
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            try {
                int rs = channel.read(dst);
                this.readtime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.send(src, remoteAddress);
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            try {
                int rs = channel.send(src, remoteAddress);
                this.writetime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public final void close() throws IOException {
            super.close();
            if (client) channel.close();
        }

        @Override
        public final boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public final boolean isTCP() {
            return false;
        }
    }

    public static AsyncConnection create(final DatagramChannel ch, SocketAddress addr,
        final boolean client0, final int readTimeoutSeconds0, final int writeTimeoutSeconds0) {
        return new BIOUDPAsyncConnection(ch, addr, client0, readTimeoutSeconds0, writeTimeoutSeconds0, null, null);
    }

    public static AsyncConnection create(final DatagramChannel ch, SocketAddress addr,
        final boolean client0, final int readTimeoutSeconds0, final int writeTimeoutSeconds0,
        final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new BIOUDPAsyncConnection(ch, addr, client0, readTimeoutSeconds0, writeTimeoutSeconds0, livingCounter, closedCounter);
    }

    private static class BIOTCPAsyncConnection extends AsyncConnection {

        private int readTimeoutSeconds;

        private int writeTimeoutSeconds;

        private final Socket socket;

        private final ReadableByteChannel readChannel;

        private final WritableByteChannel writeChannel;

        private final SocketAddress remoteAddress;

        public BIOTCPAsyncConnection(final Socket socket, final SocketAddress addr0, final int readTimeoutSeconds0, final int writeTimeoutSeconds0,
            final AtomicLong livingCounter, final AtomicLong closedCounter) {
            this.socket = socket;
            ReadableByteChannel rc = null;
            WritableByteChannel wc = null;
            try {
                socket.setSoTimeout(Math.max(readTimeoutSeconds0, writeTimeoutSeconds0));
                rc = Channels.newChannel(socket.getInputStream());
                wc = Channels.newChannel(socket.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            this.readChannel = rc;
            this.writeChannel = wc;
            this.readTimeoutSeconds = readTimeoutSeconds0;
            this.writeTimeoutSeconds = writeTimeoutSeconds0;
            SocketAddress addr = addr0;
            if (addr == null) {
                try {
                    addr = socket.getRemoteSocketAddress();
                } catch (Exception e) {
                    //do nothing
                }
            }
            this.remoteAddress = addr;
            this.livingCounter = livingCounter;
            this.closedCounter = closedCounter;
        }

        @Override
        public boolean isTCP() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return socket.getLocalSocketAddress();
        }

        @Override
        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        @Override
        public int getWriteTimeoutSeconds() {
            return writeTimeoutSeconds;
        }

        @Override
        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        @Override
        public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
        }

        @Override
        public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = 0;
                for (int i = offset; i < offset + length; i++) {
                    rs += writeChannel.write(srcs[i]);
                }
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = readChannel.read(dst);
                this.readtime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            read(dst, attachment, handler);
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            try {
                int rs = readChannel.read(dst);
                this.readtime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = writeChannel.write(src);
                this.writetime = System.currentTimeMillis();
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            try {
                int rs = writeChannel.write(src);
                this.writetime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            this.socket.close();
        }

        @Override
        public boolean isOpen() {
            return !socket.isClosed();
        }
    }

    /**
     * 通常用于 ssl socket
     *
     * @param socket Socket对象
     *
     * @return 连接对象
     */
    public static AsyncConnection create(final Socket socket) {
        return create(socket, null, 0, 0);
    }

    public static AsyncConnection create(final Socket socket, final SocketAddress addr0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
        return new BIOTCPAsyncConnection(socket, addr0, readTimeoutSecond0, writeTimeoutSecond0, null, null);
    }

    public static AsyncConnection create(final Socket socket, final SocketAddress addr0, final int readTimeoutSecond0,
        final int writeTimeoutSecond0, final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new BIOTCPAsyncConnection(socket, addr0, readTimeoutSecond0, writeTimeoutSecond0, livingCounter, closedCounter);
    }

    private static class AIOTCPAsyncConnection extends AsyncConnection {

        private int readTimeoutSeconds;

        private int writeTimeoutSeconds;

        private final AsynchronousSocketChannel channel;

        private final SocketAddress remoteAddress;

        public AIOTCPAsyncConnection(final AsynchronousSocketChannel ch, SSLContext sslContext,
            final SocketAddress addr0, final int readTimeoutSeconds, final int writeTimeoutSeconds,
            final AtomicLong livingCounter, final AtomicLong closedCounter) {
            this.channel = ch;
            this.sslContext = sslContext;
            this.readTimeoutSeconds = readTimeoutSeconds;
            this.writeTimeoutSeconds = writeTimeoutSeconds;
            SocketAddress addr = addr0;
            if (addr == null) {
                try {
                    addr = ch.getRemoteAddress();
                } catch (Exception e) {
                    //do nothing
                }
            }
            this.remoteAddress = addr;
            this.livingCounter = livingCounter;
            this.closedCounter = closedCounter;
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            this.readtime = System.currentTimeMillis();
            if (readTimeoutSeconds > 0) {
                channel.read(dst, readTimeoutSeconds, TimeUnit.SECONDS, attachment, handler);
            } else {
                channel.read(dst, attachment, handler);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            this.readtime = System.currentTimeMillis();
            channel.read(dst, timeout < 0 ? 0 : timeout, unit, attachment, handler);
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            this.writetime = System.currentTimeMillis();
            if (writeTimeoutSeconds > 0) {
                channel.write(src, writeTimeoutSeconds, TimeUnit.SECONDS, attachment, handler);
            } else {
                channel.write(src, attachment, handler);
            }
        }

        @Override
        public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, final CompletionHandler<Integer, ? super A> handler) {
            this.writetime = System.currentTimeMillis();
            channel.write(srcs, offset, length, writeTimeoutSeconds > 0 ? writeTimeoutSeconds : 60, TimeUnit.SECONDS,
                attachment, new CompletionHandler<Long, A>() {

                @Override
                public void completed(Long result, A attachment) {
                    handler.completed(result.intValue(), attachment);
                }

                @Override
                public void failed(Throwable exc, A attachment) {
                    handler.failed(exc, attachment);
                }

            });
        }

        @Override
        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        @Override
        public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
        }

        @Override
        public int getReadTimeoutSeconds() {
            return this.readTimeoutSeconds;
        }

        @Override
        public int getWriteTimeoutSeconds() {
            return this.writeTimeoutSeconds;
        }

        @Override
        public final SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return channel.getLocalAddress();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public final Future<Integer> read(ByteBuffer dst) {
            return channel.read(dst);
        }

        @Override
        public final Future<Integer> write(ByteBuffer src) {
            return channel.write(src);
        }

        @Override
        public final void close() throws IOException {
            super.close();
            channel.close();
        }

        @Override
        public final boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public final boolean isTCP() {
            return true;
        }

    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch) {
        return create(ch, null, 0, 0);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, final SocketAddress addr0, final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        return new AIOTCPAsyncConnection(ch, null, addr0, readTimeoutSeconds, writeTimeoutSeconds, null, null);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, SSLContext sslContext, final SocketAddress addr0, final int readTimeoutSeconds, final int writeTimeoutSeconds) {
        return new AIOTCPAsyncConnection(ch, sslContext, addr0, readTimeoutSeconds, writeTimeoutSeconds, null, null);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, final SocketAddress addr0, final Context context) {
        return new AIOTCPAsyncConnection(ch, context.sslContext, addr0, context.readTimeoutSeconds, context.writeTimeoutSeconds, null, null);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, final SocketAddress addr0, final int readTimeoutSeconds,
        final int writeTimeoutSeconds, final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new AIOTCPAsyncConnection(ch, null, addr0, readTimeoutSeconds, writeTimeoutSeconds, livingCounter, closedCounter);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, SSLContext sslContext, final SocketAddress addr0, final int readTimeoutSeconds,
        final int writeTimeoutSeconds, final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new AIOTCPAsyncConnection(ch, sslContext, addr0, readTimeoutSeconds, writeTimeoutSeconds, livingCounter, closedCounter);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, final SocketAddress addr0,
        final Context context, final AtomicLong livingCounter, final AtomicLong closedCounter) {
        return new AIOTCPAsyncConnection(ch, context.sslContext, addr0, context.readTimeoutSeconds, context.writeTimeoutSeconds, livingCounter, closedCounter);
    }
}
