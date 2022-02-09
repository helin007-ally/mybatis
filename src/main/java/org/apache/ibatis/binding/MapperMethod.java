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
package org.apache.ibatis.binding;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * MapperMethod 是最终执行 SQL 语句的地方，同时也记录了 Mapper 接口中的对应方法
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  //维护了关联sql语句的相关信息
  private final SqlCommand command;

  //维护了Mapper接口总方法的相关信息
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  /**
   * 根据要执行的 SQL 语句的具体类型执行 SqlSession 的相应方法完成数据库操作
   *
   * @param sqlSession
   * @param args
   * @return
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    //判断sql语句的类型
    switch (command.getType()) {
      case INSERT: {
        // 通过ParamNameResolver.getNamedParams()方法将方法的实参与参数的名称关联起来
        Object param = method.convertArgsToSqlCommandParam(args);
        // 通过SqlSession.insert()方法执行INSERT语句
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        // 通过SqlSession.update()方法执行UPDATE语句
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        //根据不同的类型回调用SqlSession.select()、SqlSession.selectList() 、SqlSession.selectMap()等底层方法完成查询
        if (method.returnsVoid() && method.hasResultHandler()) {
          // 如果方法返回值为void，且参数中包含了ResultHandler类型的实参， 则查询的结果集将会由ResultHandler对象进行处理
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          // executeForMany()方法处理返回值为集合或数组的场景
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        } else {
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
          if (method.returnsOptional()
            && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
        + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  /**
   * 根据方法的返回值类型对结果进行转换
   * Mapper 方法返回值为 void，则忽略 SQL 语句的 int 返回值，直接返回 null；
   * <p>
   * Mapper 方法返回值为 int 或 Integer 类型，则将 SQL 语句返回的 int 值直接返回；
   * <p>
   * Mapper 方法返回值为 long 或 Long 类型，则将 SQL 语句返回的 int 值转换成 long 类型之后返回；
   * <p>
   * Mapper 方法返回值为 boolean 或 Boolean 类型，则将 SQL 语句返回的 int 值与 0 比较大小，并将比较结果返回。
   *
   * @param rowCount
   * @return
   */
  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long) rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
      && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
        + " needs either a @ResultMap annotation, a @ResultType annotation,"
        + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[]) array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    //    记录关联sQl语句的唯一标识
    private final String name;
    // sql 语句类型
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 获取Mapper接口中对应的方法名称
      final String methodName = method.getName();
      // 获取Mapper接口的类型
      final Class<?> declaringClass = method.getDeclaringClass();
      // 将Mapper接口名称和方法名称拼接起来作为SQL语句唯一标识，
      // 到Configuration这个全局配置对象中查找SQL语句
      // MappedStatement对象就是Mapper.xml配置文件中一条SQL语句解析之后得到的对象
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
        configuration);
      if (ms == null) {
        // 针对@Flush注解的处理
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {// 没有@Flush注解，会抛出异常
          throw new BindingException("Invalid bound statement (not found): "
            + mapperInterface.getName() + "." + methodName);
        }
      } else {
        // 记录SQL语句唯一标识
        name = ms.getId();
        // 记录SQL语句的操作类型
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    /**
     * 根据SQL语句的唯一标识从configuration全局配置对象中查找关联的MappedStatement对象,
     * 还会顺着继承树一直往上查找,直到查找到关联的对象为止
     *
     * @param mapperInterface
     * @param methodName
     * @param declaringClass
     * @param configuration
     * @return
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
                                                   Class<?> declaringClass, Configuration configuration) {
      //将mapper接口名称和方法名称拼接起来作为sql语句唯一标识
      String statementId = mapperInterface.getName() + "." + methodName;
      // 检测Configuration中是否包含相应的MappedStatement对象
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {
        // 如果方法就定义在当前接口中，则证明没有对应的SQL语句，返回null
        return null;
      }
      //如果当前检查的Mapper接口(mapperInterface)中不是定义该方法的接口(declaringClass)
      // 则会从mapperInterface开始，沿着继承关系向上查找递归每个接口，
      // 查找该方法对应的MappedStatement对象
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
            declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    //表示方法返回值的类型 Collection 集合或数组、Map 集合、void、Cursor、Optional 类型
    private final boolean returnsMany;
    private final boolean returnsMap;
    private final boolean returnsVoid;
    private final boolean returnsCursor;
    private final boolean returnsOptional;

    //方法返回值的具体类型
    private final Class<?> returnType;
    //如果方法的返回值为map,则通过mapKey资金端记录作为可以的列名.mapKey字段的值是通过解析方法上的@MapKey注解得到的
    private final String mapKey;
    //记录了 Mapper 接口方法的参数列表中 ResultHandler 类型参数的位置
    private final Integer resultHandlerIndex;
    //录了 Mapper 接口方法的参数列表中 RowBounds 类型参数的位置
    private final Integer rowBoundsIndex;
    //用来解析方法参数列表的工具类
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 通过TypeParameterResolver工具类解析方法的返回值类型，初始化returnType字段值，省略该解析部分代码
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      // 根据返回值类型，初始化returnsVoid、returnsMany、returnsCursor、returnsMap、returnsOptional这五个与方法返回值类型相关的字段
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.returnsOptional = Optional.class.equals(this.returnType);
      // 如果返回值为Map类型，则从方法的@MapKey注解中获取Map中为key的字段名称
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;
      // 解析方法中RowBounds类型参数以及ResultHandler类型参数的下标索引位置，
      // 初始化rowBoundsIndex和resultHandlerIndex字段
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      // 创建ParamNameResolver工具对象，在创建ParamNameResolver对象的时候，会解析方法的参数列表信息
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     *
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    /**
     * 查找目标类型参数的下标索引位置，其核心原理就是遍历方法的参数列表，逐个匹配参数的类型是否为目标类型，如果匹配成功，则会返回当前参数的下标索引。
     *
     * @param method
     * @param paramType
     * @return
     */
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    public String getMapKey() {
      return mapKey;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
