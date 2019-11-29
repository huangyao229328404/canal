package com.alibaba.otter.canal.client.adapter.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI 类加载器
 *
 * @author rewerma 2018-8-19 下午11:30:49
 * @version 1.0.0
 */
public class ExtensionLoader<T> {

    private static final Logger                                      logger                     = LoggerFactory
        .getLogger(ExtensionLoader.class);

    private static final String                                      SERVICES_DIRECTORY         = "META-INF/services/";

    private static final String                                      CANAL_DIRECTORY            = "META-INF/canal/";

    private static final String                                      DEFAULT_CLASSLOADER_POLICY = "internal";

    private static final Pattern                                     NAME_SEPARATOR             = Pattern
        .compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS          = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Class<?>, Object>             EXTENSION_INSTANCES        = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Object>               EXTENSION_KEY_INSTANCE     = new ConcurrentHashMap<>();

    private final Class<?>                                           type;

    private final String                                             classLoaderPolicy;

    private final ConcurrentMap<Class<?>, String>                    cachedNames                = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>>                      cachedClasses              = new Holder<>();

    private final ConcurrentMap<String, Holder<Object>>              cachedInstances            = new ConcurrentHashMap<>();

    private String                                                   cachedDefaultName;

    private ConcurrentHashMap<String, IllegalStateException>         exceptions                 = new ConcurrentHashMap<>();

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return getExtensionLoader(type, DEFAULT_CLASSLOADER_POLICY);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type, String classLoaderPolicy) {
        if (type == null) throw new IllegalArgumentException("Extension type == null");
        //传入的class需要是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        //需要为@SPI注释的接口
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not extension, because WITHOUT @"
                                               + SPI.class.getSimpleName() + " Annotation!");
        }
        //进行内存缓存
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type, classLoaderPolicy));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private ExtensionLoader(Class<?> type, String classLoaderPolicy){
        this.type = type;
        this.classLoaderPolicy = classLoaderPolicy;
    }

    /**
     * 返回指定名字的扩展
     *
     * @param name es6,rdb
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) {
            return getDefaultExtension();
        }

        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    @SuppressWarnings("unchecked")
    public T getExtension(String name, String key) {
        if (name == null || name.length() == 0) throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        String extKey = name + "-" + StringUtils.trimToEmpty(key);
        Holder<Object> holder = cachedInstances.get(extKey);
        if (holder == null) {
            cachedInstances.putIfAbsent(extKey, new Holder<>());
            holder = cachedInstances.get(extKey);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name, key);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 返回缺省的扩展，如果没有设置则返回<code>null</code>
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0 || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: class could not be found");
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: " + t.getMessage(),
                t);
        }
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, String key) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: class could not be found");
        }
        try {
            T instance = (T) EXTENSION_KEY_INSTANCE.get(name + "-" + key);
            if (instance == null) {
                EXTENSION_KEY_INSTANCE.putIfAbsent(name + "-" + key, clazz.newInstance());
                instance = (T) EXTENSION_KEY_INSTANCE.get(name + "-" + key);
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: " + t.getMessage(),
                t);
        }
    }

    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }

        return classes;
    }

    private String getJarDirectoryPath() {
        URL url = Thread.currentThread().getContextClassLoader().getResource("");
        String dirtyPath;
        if (url != null) {
            dirtyPath = url.toString();
        } else {
            File file = new File("");
            dirtyPath = file.getAbsolutePath();
        }
        String jarPath = dirtyPath.replaceAll("^.*file:/", ""); // removes
                                                                // file:/ and
                                                                // everything
                                                                // before it
        jarPath = jarPath.replaceAll("jar!.*", "jar"); // removes everything
                                                       // after .jar, if .jar
                                                       // exists in dirtyPath
        jarPath = jarPath.replaceAll("%20", " "); // necessary if path has
                                                  // spaces within
        if (!jarPath.endsWith(".jar")) { // this is needed if you plan to run
                                         // the app using Spring Tools Suit play
                                         // button.
            jarPath = jarPath.replaceAll("/classes/.*", "/classes/");
        }
        Path path = Paths.get(jarPath).getParent(); // Paths - from java 8
        if (path != null) {
            return path.toString();
        }
        return null;
    }

    /**
     * 加载适配器插件的类与key的对应关系
     * 插件路径:client-adapter/launcher/target/canal-adapter/plugin
     * 结构: 例子es6=ES6xAdapter.class
     * @return
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        //接口上的@SPI("logger")标识默认为logger
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            //不支持默认多个外部适配器
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                                                    + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();

        // 1. plugin folder，customized extension classLoader （jar_dir/plugin）
        // 1. 从插件文件目录将所有的插件jar加载进来，通过spi的形式(spi的配置文件为ETA-INF/canal/和ETA-INF/services)
        String dir = File.separator + this.getJarDirectoryPath() + File.separator + "plugin";

        File externalLibDir = new File(dir);
        if (!externalLibDir.exists()) {
            externalLibDir = new File(File.separator + this.getJarDirectoryPath() + File.separator + "canal-adapter"
                                      + File.separator + "plugin");
        }
        logger.info("extension classpath dir: " + externalLibDir.getAbsolutePath());
        if (externalLibDir.exists()) {
            File[] files = externalLibDir.listFiles((dir1, name) -> name.endsWith(".jar"));
            if (files != null) {
                for (File f : files) {
                    URL url;
                    try {
                        url = f.toURI().toURL();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException("load extension jar failed!", e);
                    }

                    ClassLoader parent = Thread.currentThread().getContextClassLoader();
                    URLClassLoader localClassLoader;
                    if (classLoaderPolicy == null || "".equals(classLoaderPolicy)
                        || DEFAULT_CLASSLOADER_POLICY.equalsIgnoreCase(classLoaderPolicy)) {
                        localClassLoader = new URLClassExtensionLoader(new URL[] { url });
                    } else {
                        localClassLoader = new URLClassLoader(new URL[] { url }, parent);
                    }
                    /*将指定目录下的插件进行加载，获取到对应的适配器
                       key=对应适配器名，如es6,rdb,value=对应适配器的实现类*/
                    //META-INF/canal/目录下的spi配置
                    loadFile(extensionClasses, CANAL_DIRECTORY, localClassLoader);
                    //META-INF/services/目录下的spi配置
                    loadFile(extensionClasses, SERVICES_DIRECTORY, localClassLoader);
                }
            }
        }
        // 只加载外部spi, 不加载classpath
        // 2. load inner extension class with default classLoader
        // ClassLoader classLoader = findClassLoader();
        // loadFile(extensionClasses, CANAL_DIRECTORY, classLoader);
        // loadFile(extensionClasses, SERVICES_DIRECTORY, classLoader);

        return extensionClasses;
    }

    /**
     * 将指定目录下的插件进行加载，获取到对应的适配器
     * key=对应适配器名，如es6,rdb,value=对应适配器的实现类
     * @param extensionClasses
     * @param dir
     * @param classLoader
     */
    private void loadFile(Map<String, Class<?>> extensionClasses, String dir, ClassLoader classLoader) {
        String fileName = dir + type.getName();
        try {
            Enumeration<URL> urls;
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    try {
                        BufferedReader reader = null;
                        try {
                            reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                final int ci = line.indexOf('#');
                                if (ci >= 0) line = line.substring(0, ci);
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        int i = line.indexOf('=');
                                        if (i > 0) {
                                            name = line.substring(0, i).trim();
                                            line = line.substring(i + 1).trim();
                                        }
                                        if (line.length() > 0) {
                                            Class<?> clazz = classLoader.loadClass(line);
                                            // Class<?> clazz =
                                            // Class.forName(line, true,
                                            // classLoader);
                                            if (!type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException(
                                                    "Error when load extension class(interface: " + type
                                                                                + ", class line: " + clazz.getName()
                                                                                + "), class " + clazz.getName()
                                                                                + "is not subtype of interface.");
                                            } else {
                                                try {
                                                    clazz.getConstructor(type);
                                                } catch (NoSuchMethodException e) {
                                                    clazz.getConstructor();
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        for (String n : names) {
                                                            if (!cachedNames.containsKey(clazz)) {
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                //适配器名重复则报错
                                                                cachedNames.remove(clazz);
                                                                throw new IllegalStateException(
                                                                    "Duplicate extension " + type.getName() + " name "
                                                                                                + n + " on "
                                                                                                + c.getName() + " and "
                                                                                                + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException(
                                            "Failed to load extension class(interface: " + type + ", class line: "
                                                                                            + line + ") in " + url
                                                                                            + ", cause: "
                                                                                            + t.getMessage(),
                                            t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            if (reader != null) {
                                reader.close();
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " + type + ", class file: " + url
                                     + ") in " + url,
                            t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error(
                "Exception when load extension class(interface: " + type + ", description file: " + fileName + ").",
                t);
        }
    }

    @SuppressWarnings("unused")
    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

    private static class Holder<T> {

        private volatile T value;

        private void set(T value) {
            this.value = value;
        }

        private T get() {
            return value;
        }

    }
}
