/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.boot;

import static org.redkale.boot.Application.*;
import org.redkale.boot.ClassFilter.FilterEntry;
import org.redkale.net.sncp.ServiceWrapper;
import org.redkale.net.Server;
import org.redkale.net.sncp.Sncp;
import org.redkale.service.Service;
import org.redkale.util.AnyValue;
import org.redkale.util.AnyValue.DefaultAnyValue;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;
import javax.annotation.*;
import javax.persistence.*;
import org.redkale.net.*;
import org.redkale.net.sncp.*;
import org.redkale.service.*;
import org.redkale.source.*;
import org.redkale.util.*;

/**
 *
 * <p>
 * 详情见: http://www.redkale.org
 *
 * @author zhangjx
 */
public abstract class NodeServer {

    //INFO日志的换行符
    public static final String LINE_SEPARATOR = "\r\n";

    //日志输出对象
    protected final Logger logger;

    //日志是否为FINE级别
    protected final boolean fine;

    //日志是否为FINE级别
    protected final boolean finer;

    //进程主类
    protected final Application application;

    //依赖注入工厂类
    protected final ResourceFactory factory;

    //当前Server对象
    protected final Server server;

    private String sncpGroup = null;  //当前Server的SNCP协议的组

    private InetSocketAddress sncpAddress; //SNCP服务的地址， 非SNCP为null

    protected Consumer<ServiceWrapper> consumer;

    protected AnyValue serverConf;

    protected final Set<ServiceWrapper> localServiceWrappers = new LinkedHashSet<>();

    protected final Set<ServiceWrapper> remoteServiceWrappers = new LinkedHashSet<>();

    public NodeServer(Application application, Server server) {
        this.application = application;
        this.factory = application.getResourceFactory().createChild();
        this.server = server;
        this.logger = Logger.getLogger(this.getClass().getSimpleName());
        this.fine = logger.isLoggable(Level.FINE);
        this.finer = logger.isLoggable(Level.FINER);
    }

    protected Consumer<Runnable> getExecutor() throws Exception {
        if (server == null) return null;
        final Field field = Server.class.getDeclaredField("context");
        field.setAccessible(true);
        return new Consumer<Runnable>() {

            private Context context;

            @Override
            public void accept(Runnable t) {
                if (context == null && server != null) {
                    try {
                        this.context = (Context) field.get(server);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Server (" + server.getSocketAddress() + ") cannot find Context", e);
                    }
                }
                context.submit(t);
            }

        };
    }

    public static <T extends NodeServer> NodeServer create(Class<T> clazz, Application application, AnyValue serconf) {
        try {
            return clazz.getConstructor(Application.class, AnyValue.class).newInstance(application, serconf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void init(AnyValue config) throws Exception {
        this.serverConf = config == null ? AnyValue.create() : config;
        if (isSNCP()) { // SNCP协议
            String host = this.serverConf.getValue("host", "0.0.0.0").replace("0.0.0.0", "");
            this.sncpAddress = new InetSocketAddress(host.isEmpty() ? application.localAddress.getHostAddress() : host, this.serverConf.getIntValue("port"));
            this.sncpGroup = application.globalNodes.get(this.sncpAddress);
            if (this.sncpGroup == null) throw new RuntimeException("Server (" + String.valueOf(config).replaceAll("\\s+", " ") + ") not found <group> info");
        }

        if (this.sncpAddress != null) this.factory.register(RESNAME_SERVER_ADDR, this.sncpAddress); //单点服务不会有 sncpAddress、sncpGroup
        if (this.sncpGroup != null) this.factory.register(RESNAME_SERVER_GROUP, this.sncpGroup);
        {
            //设置root文件夹
            String webroot = config.getValue("root", "root");
            File myroot = new File(webroot);
            if (!webroot.contains(":") && !webroot.startsWith("/")) {
                myroot = new File(System.getProperty(Application.RESNAME_APP_HOME), webroot);
            }

            factory.register(Server.RESNAME_SERVER_ROOT, String.class, myroot.getCanonicalPath());
            factory.register(Server.RESNAME_SERVER_ROOT, File.class, myroot.getCanonicalFile());
            factory.register(Server.RESNAME_SERVER_ROOT, Path.class, myroot.toPath());

            final String homepath = myroot.getCanonicalPath();
            Server.loadLib(logger, config.getValue("lib", "") + ";" + homepath + "/lib/*;" + homepath + "/classes");
            if (server != null) server.init(config);
        }

        initResource(); //给 DataSource、CacheSource 注册依赖注入时的监听回调事件。

        ClassFilter<Servlet> servletFilter = createServletClassFilter();
        ClassFilter<Service> serviceFilter = createServiceClassFilter();
        long s = System.currentTimeMillis();
        if (servletFilter == null) {
            ClassFilter.Loader.load(application.getHome(), serviceFilter);
        } else {
            ClassFilter.Loader.load(application.getHome(), serviceFilter, servletFilter);
        }
        long e = System.currentTimeMillis() - s;
        logger.info(this.getClass().getSimpleName() + " load filter class in " + e + " ms");
        loadService(serviceFilter); //必须在servlet之前
        loadServlet(servletFilter);
    }

    protected abstract void loadServlet(ClassFilter<? extends Servlet> servletFilter) throws Exception;

    private void initResource() {
        final NodeServer self = this;
        //---------------------------------------------------------------------------------------------
        final ResourceFactory regFactory = application.getResourceFactory();
        factory.add(DataSource.class, (ResourceFactory rf, final Object src, String resourceName, Field field, final Object attachment) -> {
            try {
                if (field.getAnnotation(Resource.class) == null) return;
                if ((src instanceof Service) && Sncp.isRemote((Service) src)) return; //远程模式不得注入 DataSource
                DataSource source = new DataDefaultSource(resourceName);
                application.dataSources.add(source);
                regFactory.register(resourceName, DataSource.class, source);

                SncpClient client = null;
                Transport sameGroupTransport = null;
                List<Transport> diffGroupTransports = null;
                try {
                    Field ts = src.getClass().getDeclaredField("_sameGroupTransport");
                    ts.setAccessible(true);
                    sameGroupTransport = (Transport) ts.get(src);

                    ts = src.getClass().getDeclaredField("_diffGroupTransports");
                    ts.setAccessible(true);
                    diffGroupTransports = Arrays.asList((Transport[]) ts.get(src));

                    ts = src.getClass().getDeclaredField("_client");
                    ts.setAccessible(true);
                    client = (SncpClient) ts.get(src);
                } catch (Exception e) {
                    throw new RuntimeException(src.getClass().getName() + " not found _sameGroupTransport or _diffGroupTransports at " + field, e);
                }
                final InetSocketAddress sncpAddr = client == null ? null : client.getClientAddress();
                if ((src instanceof DataSource) && sncpAddr != null && factory.find(resourceName, DataCacheListener.class) == null) { //只有DataSourceService 才能赋值 DataCacheListener
                    Service cacheListenerService = Sncp.createLocalService(resourceName, getExecutor(), DataCacheListenerService.class, sncpAddr, sameGroupTransport, diffGroupTransports);
                    regFactory.register(resourceName, DataCacheListener.class, cacheListenerService);
                    final NodeSncpServer sncpServer = application.findNodeSncpServer(sncpAddr);
                    Set<String> gs = application.findSncpGroups(sameGroupTransport, diffGroupTransports);
                    ServiceWrapper wrapper = new ServiceWrapper(DataCacheListenerService.class, cacheListenerService, resourceName, sncpServer.getSncpGroup(), gs, null);
                    localServiceWrappers.add(wrapper);
                    sncpServer.consumerAccept(wrapper);
                    rf.inject(cacheListenerService, self);
                    if (fine) logger.fine("[" + Thread.currentThread().getName() + "] Load Service " + wrapper.getService());
                }
                field.set(src, source);
                rf.inject(source, self); // 给 "datasource.nodeid" 赋值;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "DataSource inject error", e);
            }
        });
        factory.add(CacheSource.class, (ResourceFactory rf, final Object src, final String resourceName, Field field, final Object attachment) -> {
            try {
                if (field.getAnnotation(Resource.class) == null) return;
                if ((src instanceof Service) && Sncp.isRemote((Service) src)) return; //远程模式不得注入 CacheSource   

                SncpClient client = null;
                Transport sameGroupTransport = null;
                List<Transport> diffGroupTransports = null;
                try {
                    Field ts = src.getClass().getDeclaredField("_sameGroupTransport");
                    ts.setAccessible(true);
                    sameGroupTransport = (Transport) ts.get(src);

                    ts = src.getClass().getDeclaredField("_diffGroupTransports");
                    ts.setAccessible(true);
                    Transport[] dts = (Transport[]) ts.get(src);
                    if (dts != null) diffGroupTransports = Arrays.asList(dts);

                    ts = src.getClass().getDeclaredField("_client");
                    ts.setAccessible(true);
                    client = (SncpClient) ts.get(src);
                } catch (Exception e) {
                    throw new RuntimeException(src.getClass().getName() + " not found _sameGroupTransport or _diffGroupTransports at " + field, e);
                }
                final InetSocketAddress sncpAddr = client == null ? null : client.getClientAddress();
                final CacheSourceService source = Sncp.createLocalService(resourceName, getExecutor(), CacheSourceService.class, sncpAddr, sameGroupTransport, diffGroupTransports);
                Type genericType = field.getGenericType();
                ParameterizedType pt = (genericType instanceof ParameterizedType) ? (ParameterizedType) genericType : null;
                Type valType = pt == null ? null : pt.getActualTypeArguments()[1];
                source.setStoreType(pt == null ? Serializable.class : (Class) pt.getActualTypeArguments()[0], valType instanceof Class ? (Class) valType : Object.class);
                if (field.getAnnotation(Transient.class) != null) source.setNeedStore(false); //必须在setStoreType之后
                application.cacheSources.add(source);
                regFactory.register(resourceName, CacheSource.class, source);
                field.set(src, source);
                rf.inject(source, self); //
                ((Service) source).init(null);

                if ((src instanceof WebSocketNodeService) && sncpAddr != null) { //只有WebSocketNodeService的服务才需要给SNCP服务注入CacheSourceService
                    NodeSncpServer sncpServer = application.findNodeSncpServer(sncpAddr);
                    Set<String> gs = application.findSncpGroups(sameGroupTransport, diffGroupTransports);
                    ServiceWrapper wrapper = new ServiceWrapper(CacheSourceService.class, (Service) source, resourceName, sncpServer.getSncpGroup(), gs, null);
                    sncpServer.getSncpServer().addService(wrapper);
                    if (finer) logger.finer("[" + Thread.currentThread().getName() + "] Load Service " + wrapper.getService());
                }
                logger.finer("[" + Thread.currentThread().getName() + "] Load Source " + source);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "DataSource inject error", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    protected void loadService(ClassFilter serviceFilter) throws Exception {
        if (serviceFilter == null) return;
        final String threadName = "[" + Thread.currentThread().getName() + "] ";
        final Set<FilterEntry<Service>> entrys = serviceFilter.getFilterEntrys();
        ResourceFactory regFactory = isSNCP() ? application.getResourceFactory() : factory;

        for (FilterEntry<Service> entry : entrys) { //service实现类
            final Class<? extends Service> type = entry.getType();
            if (Modifier.isFinal(type.getModifiers())) continue; //修饰final的类跳过
            if (!Modifier.isPublic(type.getModifiers())) continue;
            if (entry.getName().contains("$")) throw new RuntimeException("<name> value cannot contains '$' in " + entry.getProperty());
            if (factory.find(entry.getName(), type) != null) continue; //Server加载Service时需要判断是否已经加载过了。
            final HashSet<String> groups = entry.getGroups(); //groups.isEmpty()表示<services>没有配置groups属性。
            if (groups.isEmpty() && isSNCP()) groups.add(this.sncpGroup);

            final boolean localed = this.sncpAddress == null //非SNCP的Server，通常是单点服务
                    || groups.contains(this.sncpGroup) //本地IP含在内的
                    || type.getAnnotation(LocalService.class) != null;//本地模式
            if (localed && (type.isInterface() || Modifier.isAbstract(type.getModifiers()))) continue; //本地模式不能实例化接口和抽象类的Service类

            Service service;
            if (localed) { //本地模式
                service = Sncp.createLocalService(entry.getName(), getExecutor(), type, this.sncpAddress, loadTransport(this.sncpGroup), loadTransports(groups));
            } else {
                service = Sncp.createRemoteService(entry.getName(), getExecutor(), type, this.sncpAddress, loadTransport(groups));
            }
            final ServiceWrapper wrapper = new ServiceWrapper(type, service, entry.getName(), localed ? this.sncpGroup : null, groups, entry.getProperty());
            if (factory.find(wrapper.getName(), wrapper.getType()) == null) {
                regFactory.register(wrapper.getName(), wrapper.getService());
                if (wrapper.isRemote()) {
                    remoteServiceWrappers.add(wrapper);
                } else {
                    localServiceWrappers.add(wrapper);
                    if (consumer != null) consumer.accept(wrapper);
                }
            } else if (isSNCP() && !entry.isAutoload()) {
                throw new RuntimeException(ServiceWrapper.class.getSimpleName() + "(class:" + type.getName() + ", name:" + entry.getName() + ", group:" + groups + ") is repeat.");
            }
        }
        application.servicecdl.countDown();
        application.servicecdl.await();

        final StringBuilder sb = logger.isLoggable(Level.INFO) ? new StringBuilder() : null;
        //---------------- inject ----------------
        new ArrayList<>(localServiceWrappers).forEach(y -> {
            factory.inject(y.getService(), NodeServer.this);
        });
        remoteServiceWrappers.forEach(y -> {
            factory.inject(y.getService(), NodeServer.this);
            if (sb != null) {
                sb.append(threadName).append(y.toSimpleString()).append(" loaded and injected").append(LINE_SEPARATOR);
            }
        });
        //----------------- init -----------------
        List<ServiceWrapper> swlist = new ArrayList<>(localServiceWrappers);
        Collections.sort(swlist);
        localServiceWrappers.clear();
        localServiceWrappers.addAll(swlist);
        final List<String> slist = sb == null ? null : new CopyOnWriteArrayList<>();
        localServiceWrappers.parallelStream().forEach(y -> {
            long s = System.currentTimeMillis();
            y.getService().init(y.getConf());
            long e = System.currentTimeMillis() - s;
            if (slist != null) slist.add(new StringBuilder().append(threadName).append(y.toSimpleString()).append(" loaded and init ").append(e).append(" ms").append(LINE_SEPARATOR).toString());
        });
        Collections.sort(slist);
        if (slist != null && sb != null) {
            for (String s : slist) {
                sb.append(s);
            }
        }
        if (sb != null && sb.length() > 0) logger.log(Level.INFO, sb.toString());
    }

    protected List<Transport> loadTransports(final HashSet<String> groups) {
        if (groups == null) return null;
        final List<Transport> transports = new ArrayList<>();
        for (String group : groups) {
            if (this.sncpGroup == null || !this.sncpGroup.equals(group)) {
                transports.add(loadTransport(group));
            }
        }
        return transports;
    }

    protected Transport loadTransport(final HashSet<String> groups) {
        if (groups == null || groups.isEmpty()) return null;
        List<String> tmpgroup = new ArrayList<>(groups);
        Collections.sort(tmpgroup);  //按字母排列顺序
        boolean flag = false;
        StringBuilder sb = new StringBuilder();
        for (String g : tmpgroup) {
            if (flag) sb.append(';');
            sb.append(g);
            flag = true;
        }
        final String groupid = sb.toString();
        Transport transport = application.transports.get(groupid);
        if (transport != null) return transport;
        final List<Transport> transports = new ArrayList<>();
        for (String group : groups) {
            transports.add(loadTransport(group));
        }
        Set<InetSocketAddress> addrs = new HashSet();
        for (Transport t : transports) {
            for (InetSocketAddress addr : t.getRemoteAddresses()) {
                addrs.add(addr);
            }
        }
        Transport first = transports.get(0);
        Transport newTransport = new Transport(groupid, application.findGroupProtocol(first.getName()), application.getWatchFactory(),
                application.transportBufferPool, application.transportChannelGroup, this.sncpAddress, addrs);
        synchronized (application.transports) {
            transport = application.transports.get(groupid);
            if (transport == null) {
                transport = newTransport;
                application.transports.put(groupid, transport);
            }
        }
        return transport;
    }

    protected Transport loadTransport(final String group) {
        if (group == null) return null;
        Transport transport;
        synchronized (application.transports) {
            transport = application.transports.get(group);
            if (transport != null) {
                if (this.sncpAddress != null && !this.sncpAddress.equals(transport.getClientAddress())) {
                    throw new RuntimeException(transport + "repeat create on newClientAddress = " + this.sncpAddress + ", oldClientAddress = " + transport.getClientAddress());
                }
                return transport;
            }
            Set<InetSocketAddress> addrs = application.findGlobalGroup(group);
            if (addrs == null) throw new RuntimeException("Not found <group> = " + group + " on <resources> ");
            transport = new Transport(group, application.findGroupProtocol(group), application.getWatchFactory(),
                    application.transportBufferPool, application.transportChannelGroup, this.sncpAddress, addrs);
            application.transports.put(group, transport);
        }
        return transport;
    }

    protected abstract ClassFilter<Servlet> createServletClassFilter();

    protected ClassFilter<Service> createServiceClassFilter() {
        return createClassFilter(this.sncpGroup, null, Service.class, Annotation.class, "services", "service");
    }

    protected ClassFilter createClassFilter(final String localGroup, Class<? extends Annotation> ref,
            Class inter, Class<? extends Annotation> ref2, String properties, String property) {
        ClassFilter cf = new ClassFilter(ref, inter, null);
        if (properties == null && properties == null) return cf;
        if (this.serverConf == null) return cf;
        AnyValue[] proplist = this.serverConf.getAnyValues(properties);
        if (proplist == null || proplist.length < 1) return cf;
        cf = null;
        for (AnyValue list : proplist) {
            DefaultAnyValue prop = null;
            String sc = list.getValue("groups");
            if (sc != null) {
                sc = sc.trim();
                if (sc.endsWith(";")) sc = sc.substring(0, sc.length() - 1);
            }
            if (sc == null) sc = localGroup;
            if (sc != null) {
                prop = new AnyValue.DefaultAnyValue();
                prop.addValue("groups", sc);
            }
            ClassFilter filter = new ClassFilter(ref, inter, prop);
            for (AnyValue av : list.getAnyValues(property)) {
                final AnyValue[] items = av.getAnyValues("property");
                if (av instanceof DefaultAnyValue && items.length > 0) {
                    DefaultAnyValue dav = DefaultAnyValue.create();
                    final AnyValue.Entry<String>[] strings = av.getStringEntrys();
                    if (strings != null) {
                        for (AnyValue.Entry<String> en : strings) {
                            dav.addValue(en.name, en.getValue());
                        }
                    }
                    final AnyValue.Entry<AnyValue>[] anys = av.getAnyEntrys();
                    if (anys != null) {
                        for (AnyValue.Entry<AnyValue> en : anys) {
                            if (!"property".equals(en.name)) dav.addValue(en.name, en.getValue());
                        }
                    }
                    DefaultAnyValue ps = DefaultAnyValue.create();
                    for (AnyValue item : items) {
                        ps.addValue(item.getValue("name"), item.getValue("value"));
                    }
                    dav.addValue("property", ps);
                    av = dav;
                }
                filter.filter(av, av.getValue("value"), false);
            }
            if (list.getBoolValue("autoload", true)) {
                String includes = list.getValue("includes", "");
                String excludes = list.getValue("excludes", "");
                filter.setIncludePatterns(includes.split(";"));
                filter.setExcludePatterns(excludes.split(";"));
            } else if (ref2 == null || ref2 == Annotation.class) {  //service如果是autoload=false则不需要加载
                filter.setRefused(true);
            } else if (ref2 != Annotation.class) {
                filter.setAnnotationClass(ref2);
            }
            cf = (cf == null) ? filter : cf.or(filter);
        }
        return cf;
    }

    public abstract InetSocketAddress getSocketAddress();

    public boolean isSNCP() {
        return false;
    }

    public InetSocketAddress getSncpAddress() {
        return sncpAddress;
    }

    public String getSncpGroup() {
        return sncpGroup;
    }

    public void start() throws IOException {
        server.start();
    }

    public void shutdown() throws IOException {
        final StringBuilder sb = logger.isLoggable(Level.INFO) ? new StringBuilder() : null;
        localServiceWrappers.forEach(y -> {
            long s = System.currentTimeMillis();
            y.getService().destroy(y.getConf());
            long e = System.currentTimeMillis() - s;
            if (e > 2 && sb != null) {
                sb.append(y.toSimpleString()).append(" destroy ").append(e).append("ms").append(LINE_SEPARATOR);
            }
        });
        if (sb != null && sb.length() > 0) logger.log(Level.INFO, sb.toString());
        server.shutdown();
    }

}