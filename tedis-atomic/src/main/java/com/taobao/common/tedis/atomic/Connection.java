/**
 * (C) 2011-2012 Alibaba Group Holding Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 *
 */
package com.taobao.common.tedis.atomic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import com.taobao.common.tedis.TedisConnectionException;
import com.taobao.common.tedis.TedisDataException;
import com.taobao.common.tedis.TedisException;
import com.taobao.common.tedis.util.Protocol;
import com.taobao.common.tedis.util.Protocol.Command;
import com.taobao.common.tedis.util.RedisInputStream;
import com.taobao.common.tedis.util.RedisOutputStream;
import com.taobao.common.tedis.util.SafeEncoder;

public class Connection {
    private String host;
    private int port = Protocol.DEFAULT_PORT;
    private Socket socket;
    private Protocol protocol = new Protocol();
    private RedisOutputStream outputStream;
    private RedisInputStream inputStream;
    private int pipelinedCommands = 0;
    private int timeout = Protocol.DEFAULT_TIMEOUT;

    public Socket getSocket() {
        return socket;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(final int timeout) {
        this.timeout = timeout;
    }

    public void setTimeoutInfinite() {
        try {
            socket.setKeepAlive(true);
            socket.setSoTimeout(0);
        } catch (SocketException ex) {
            throw new TedisException(ex);
        }
    }

    public void rollbackTimeout() {
        try {
            socket.setSoTimeout(timeout);
            socket.setKeepAlive(false);
        } catch (SocketException ex) {
            throw new TedisException(ex);
        }
    }

    public Connection(final String host) {
        super();
        this.host = host;
    }

    protected void flush() {
        try {
            outputStream.flush();
        } catch (IOException e) {
            throw new TedisConnectionException(e);
        }
    }

    protected Connection sendCommand(final Command cmd, final String... args) {
        final byte[][] bargs = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            bargs[i] = SafeEncoder.encode(args[i]);
        }
        return sendCommand(cmd, bargs);
    }

    protected Connection sendCommand(final Command cmd, final byte[]... args) {
        connect();
        protocol.sendCommand(outputStream, cmd, args);
        pipelinedCommands++;
        return this;
    }

    protected Connection sendCommand(final Command cmd) {
        connect();
        protocol.sendCommand(outputStream, cmd, new byte[0][]);
        pipelinedCommands++;
        return this;
    }

    public Connection(final String host, final int port) {
        super();
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public Connection() {
    }

    public void connect() {
        if (!isConnected()) {
            try {
                socket = new Socket();
                socket.setReuseAddress(true);
                socket.setKeepAlive(true); // Will monitor the TCP connection is
                                           // valid
                socket.setTcpNoDelay(true); // Socket buffer Whetherclosed, to
                                            // ensure timely delivery of data
                socket.setSoLinger(true, 0); // Control calls close () method,
                                             // the underlying socket is closed
                                             // immediately

                socket.connect(new InetSocketAddress(host, port), timeout);
                socket.setSoTimeout(timeout);
                outputStream = new RedisOutputStream(socket.getOutputStream());
                inputStream = new RedisInputStream(socket.getInputStream());
            } catch (IOException ex) {
                throw new TedisConnectionException(ex);
            }
        }
    }

    public void disconnect() {
        if (isConnected()) {
            try {
                inputStream.close();
                outputStream.close();
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ex) {
                throw new TedisConnectionException(ex);
            }
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isBound() && !socket.isClosed() && socket.isConnected() && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    protected String getStatusCodeReply() {
        flush();
        pipelinedCommands--;
        final byte[] resp = (byte[]) protocol.read(inputStream);
        if (null == resp) {
            return null;
        } else {
            return SafeEncoder.encode(resp);
        }
    }

    public String getBulkReply() {
        final byte[] result = getBinaryBulkReply();
        if (null != result) {
            return SafeEncoder.encode(result);
        } else {
            return null;
        }
    }

    public byte[] getBinaryBulkReply() {
        flush();
        pipelinedCommands--;
        return (byte[]) protocol.read(inputStream);
    }

    public Long getIntegerReply() {
        flush();
        pipelinedCommands--;
        return (Long) protocol.read(inputStream);
    }

    public List<String> getMultiBulkReply() {
        return BuilderFactory.STRING_LIST.build(getBinaryMultiBulkReply());
    }

    @SuppressWarnings("unchecked")
    public List<byte[]> getBinaryMultiBulkReply() {
        flush();
        pipelinedCommands--;
        return (List<byte[]>) protocol.read(inputStream);
    }

    @SuppressWarnings("unchecked")
    public List<Object> getObjectMultiBulkReply() {
        flush();
        pipelinedCommands--;
        return (List<Object>) protocol.read(inputStream);
    }

    public List<Object> getAll() {
        return getAll(0);
    }

    public List<Object> getAll(int except) {
        List<Object> all = new ArrayList<Object>();
        flush();
        while (pipelinedCommands > except) {
            try {
                all.add(protocol.read(inputStream));
            } catch (TedisDataException e) {
                all.add(e);
            }
            pipelinedCommands--;
        }
        return all;
    }

    public Object getOne() {
        flush();
        pipelinedCommands--;
        return protocol.read(inputStream);
    }
}