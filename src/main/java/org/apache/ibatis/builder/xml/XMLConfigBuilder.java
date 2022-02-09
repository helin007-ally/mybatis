/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 *
 * 解析mybatis-config.xml全局配置文件
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  /**
   * 状态标识字段，记录当前 XMLConfigBuilder 对象是否已经成功解析完 mybatis-config.xml 配置文件。
   */
  private boolean parsed;
  /**
   * XPathParser 对象是一个 XML 解析器，这里的 parser 对象就是用来解析 mybatis-config.xml 配置文件的。
   */
  private final XPathParser parser;
  /**
   * 标签定义的环境名称。
   */
  private String environment;
  /**
   * ReflectorFactory 接口的核心功能是实现对 Reflector 对象的创建和缓存。
   */
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 触发mybatis-config.xml 配置文件的解析
   *
   * @return
   */
  public Configuration parse() {
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    //通过标签解析器解析得到XNode对象
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * 定义了解析 mybatis-config.xml 配置文件的完整流程.
   * 依次解析了xml中所有的标签
   *
   * @param root
   */
  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      //解析<properties>标签
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      //处理虚拟文件系统组件
      loadCustomVfs(settings);
      //处理日志相关组件
      loadCustomLogImpl(settings);
      typeAliasesElement(root.evalNode("typeAliases"));
      pluginElement(root.evalNode("plugins"));
      objectFactoryElement(root.evalNode("objectFactory"));
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      //设置<settings>标签配置的各个参数
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments"));
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      typeHandlerElement(root.evalNode("typeHandlers"));
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 解析 <settings> 标签，并将解析得到的配置信息记录到 Configuration 这个全局配置对象的同名属性中
   *
   * @param context
   * @return
   */

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    // 处理<settings>标签的所有子标签，也就是<setting>标签，将其name属性和value属性
    // 整理到Properties对象中保存
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 创建Configuration对应的MetaClass对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    // 检测Configuration对象中是否包含每个配置项的setter方法
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  /**
   * 解析别名信息,记录到父类的TypeAliasRegistry 别名注册中心中
   *
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析 <plugins> 标签中配置的自定义插件
   *
   * @param parent
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历全部的<plugin>子标签
      for (XNode child : parent.getChildren()) {
        // 获取每个<plugin>标签中的interceptor属性
        String interceptor = child.getStringAttribute("interceptor");
        // 获取<plugin>标签下的其他配置信息
        Properties properties = child.getChildrenAsProperties();
        // 初始化interceptor属性指定的自定义插件
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        // 初始化插件的配置
        interceptorInstance.setProperties(properties);
        // 将Interceptor对象添加到Configuration的插件链中保存，等待后续使用
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 实现了加载自定义 ObjectFactory 实现类的功能，其核心逻辑就是解析 <objectFactory> 标签中配置的自定义 ObjectFactory 实现类，并完成相关的实例化操作
   *
   * @param context
   * @throws Exception
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取<objectFactory>标签的type属性
      String type = context.getStringAttribute("type");
      // 根据type属性值，初始化自定义的ObjectFactory实现
      Properties properties = context.getChildrenAsProperties();
      // 初始化ObjectFactory对象的配置
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(properties);
      // 将ObjectFactory对象记录到Configuration这个全局配置对象中
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * propertiesElement() 方法的核心逻辑就是解析 mybatis-config.xml 配置文件中的 <properties> 标签。
   * <p>
   * 从 <properties> 标签中解析出来的 KV 信息会被记录到一个 Properties 对象（也就是 Configuration 全局配置对象的 variables 字段），
   * 在后续解析其他标签的时候，MyBatis 会使用这个 Properties 对象中记录的 KV 信息替换匹配的占位符。
   *
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      Properties defaults = context.getChildrenAsProperties();
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
  }

  /**
   * 根据 XMLConfigBuilder.environment 字段值，拿到正确的 <environment> 标签，然后解析这个环境中使用的 TransactionFactory、DataSource 等核心对象
   *
   * @param context
   * @throws Exception
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
        // 未指定使用的环境id，默认获取default值
        environment = context.getStringAttribute("default");
      }
      // 获取<environment>标签下的所有配置
      for (XNode child : context.getChildren()) {
        // 获取环境id
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {
          // 获取<transactionManager>、<dataSource>等标签，并进行解析，其中会根据配置信息初始化相应的TransactionFactory对象和DataSource对象
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 创建Environment对象，并关联创建好的TransactionFactory和DataSource
          Environment.Builder environmentBuilder = new Environment.Builder(id)
            .transactionFactory(txFactory)
            .dataSource(dataSource);
          // 将Environment对象记录到Configuration中，等待后续使用
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }

  /**
   * 在 MyBatis 中编写的都是原生的 SQL 语句，而很多数据库产品都会有一些 SQL 方言，这些方言与标准 SQL 不兼容。
   * <p>
   * 在 mybatis-config.xml 配置文件中，我们可以通过 <databaseIdProvider> 标签定义需要支持的全部数据库的 DatabaseId，
   * 在后续编写 Mapper 映射配置文件的时候，就可以为同一个业务场景定义不同的 SQL 语句（带有不同的 DataSourceId），来支持不同的数据库，
   * 这里就是靠 DatabaseId 来确定哪个 SQL 语句支持哪个数据库的。
   * databaseIdProviderElement() 的核心就是获取 DatabaseId 值
   *
   * @param context
   * @throws Exception
   */

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // 获取type属性值
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        // 兼容操作
        type = "DB_VENDOR";
      }
      // 初始化DatabaseIdProvider
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 通过DataSource获取DatabaseId，并保存到Configuration中，等待后续使用
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 解析<typeHandlers> 标签标签,TypeHandler信息记录到父类的TypeHandlerRegistry  TypeHandler注册中心中
   *
   * @param parent
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析<mappers> 标签,确定需要加载的Mapper.xml文件的位置.
   * mapperElement() 方法就是 XMLConfigBuilder 处理 <mappers> 标签的具体实现，
   * 其中会初始化 XMLMapperBuilder 对象来加载各个 Mapper.xml 映射文件。同时，还会扫描 Mapper 映射文件相应的 Mapper 接口，处理其中的注解并将 Mapper 接口注册到 MapperRegistry 中。
   *
   * @param parent
   * @throws Exception
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历每个子标签
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          // 如果指定了<package>子标签，则会扫描指定包内全部Java类型
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          // 解析<mapper>子标签，这里会获取resource、url、class三个属性，这三个属性互斥
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // 如果<mapper>子标签指定了resource或是url属性，都会创建XMLMapperBuilder对象，
          // 然后使用这个XMLMapperBuilder实例解析指定的Mapper.xml配置文件
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            try (InputStream inputStream = Resources.getUrlAsStream(url)) {
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) {
            // 如果<mapper>子标签指定了class属性，则向MapperRegistry注册class属性指定的Mapper接口
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
