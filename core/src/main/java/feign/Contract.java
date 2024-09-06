/*
 * Copyright 2012-2022 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.Request.HttpMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * 定义哪些注释和值在接口上有效
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    /**
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class)
     */
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      // 接口不能有范型
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      // 最多可以继承一个接口
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      // 结果列表
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      // 循环被 @FeignClient 注释的接口中的方法
      for (final Method method : targetType.getMethods()) {
        // Object 类中的方法不处理
        if (method.getDeclaringClass() == Object.class ||
                // 静态方法不处理
            (method.getModifiers() & Modifier.STATIC) != 0 ||
                // 接口中的  isDefault 方法不处理
            Util.isDefault(method)) {
          continue;
        }
        // 解析与验证方法原数据
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        if (result.containsKey(metadata.configKey())) {
          // todo 应该是重复校验
          MethodMetadata existingMetadata = result.get(metadata.configKey());
          Type existingReturnType = existingMetadata.returnType();
          Type overridingReturnType = metadata.returnType();
          Type resolvedType = Types.resolveReturnType(existingReturnType, overridingReturnType);
          if (resolvedType.equals(overridingReturnType)) {
            result.put(metadata.configKey(), metadata);
          }
          continue;
        }
        result.put(metadata.configKey(), metadata);
      }
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      final MethodMetadata data = new MethodMetadata();
      // 原接口Class 对象
      data.targetType(targetType);
      // 方法
      data.method(method);
      // 方法返回值类型
      data.returnType(
          Types.resolve(targetType, targetType, method.getGenericReturnType()));
      // 配置key 例子:XXXFeign#methodName(ParamClassName)
      data.configKey(Feign.configKey(targetType, method));
      if (AlwaysEncodeBodyContract.class.isAssignableFrom(this.getClass())) {
        // 如果当前 Contract Class 类型 等于 AlwaysEncodeBodyContract 或是 AlwaysEncodeBodyContract子类 设置此标识
        data.alwaysEncodeBody(true);
      }

      // 处理类上的注解
      if (targetType.getInterfaces().length == 1) {
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }

      // 处理类上的注解
      processAnnotationOnClass(data, targetType);


      for (final Annotation methodAnnotation : method.getAnnotations()) {
        // 处理方法上的注解
        processAnnotationOnMethod(data, methodAnnotation, method);
      }

      // todo 正常情况是false
      if (data.isIgnored()) {
        return data;
      }

      // 检查 http method 是否有设置
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
          data.configKey(), data.warnings());

      // 参数类型列表
      final Class<?>[] parameterTypes = method.getParameterTypes();

      // 参数类型列表(带泛型)
      final Type[] genericParameterTypes = method.getGenericParameterTypes();

      // 方法上的注解
      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      final int count = parameterAnnotations.length;
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          // 解析参数上的注解
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        // todo 如果是 HttpAnnotation 参数 这里进行记录下标
        if (isHttpAnnotation) {
          data.ignoreParamater(i);
        }

        if (parameterTypes[i] == URI.class) {
          // 如果传递了 URI 对象 ,将标记URI对象
          data.urlIndex(i);
        } else if (!isHttpAnnotation
            && !Request.Options.class.isAssignableFrom(parameterTypes[i])) {
          // 不属于 HttpAnnotation 参数 也不是 Request.Options 请求配置参数 走以下逻辑
          if (data.isAlreadyProcessed(i)) {
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else if (!data.alwaysEncodeBody()) {
            // body 传递 不能有表单参数
            checkState(data.formParams().isEmpty(),
                "Body parameters cannot be used with form parameters.%s", data.warnings());
            // body 参数只能有一个
            checkState(data.bodyIndex() == null,
                "Method has too many Body parameters: %s%s", method, data.warnings());
            data.bodyIndex(i);
            // body 参数的类型
            data.bodyType(
                Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      if (data.headerMapIndex() != null) {
        // check header map parameter for map type
        if (Map.class.isAssignableFrom(parameterTypes[data.headerMapIndex()])) {
          // 检查 Map key 必须为String
          checkMapKeys("HeaderMap", genericParameterTypes[data.headerMapIndex()]);
        }
      }

      if (data.queryMapIndex() != null) {
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          // 检查 Map key 必须为String
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class<?>) genericType).getGenericInterfaces();
        for (final Type extended : interfaces) {
          if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
            // use the first extended interface we find.
            final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
            keyClass = (Class<?>) parameterTypes[0];
            break;
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      final Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends DeclarativeContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    public Default() {
      super.registerClassAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnType = header.value();
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        final Map<String, Collection<String>> headers = toMap(headersOnType);
        headers.putAll(data.template().headers());
        data.template().headers(null); // to clear
        data.template().headers(headers);
      });
      super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
        final String requestLine = ann.value();
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        } else {
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          data.template().uri(requestLineMatcher.group(2));
        }
        data.template().decodeSlash(ann.decodeSlash());
        data.template()
            .collectionFormat(ann.collectionFormat());
      });
      super.registerMethodAnnotation(Body.class, (ann, data) -> {
        final String body = ann.value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            data.configKey());
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          data.template().bodyTemplate(body);
        }
      });
      super.registerMethodAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnMethod = header.value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        data.template().headers(toMap(headersOnMethod));
      });
      super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
        final String annotationName = paramAnnotation.value();
        final Parameter parameter = data.method().getParameters()[paramIndex];
        final String name;
        if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
          name = parameter.getName();
        } else {
          name = annotationName;
        }
        checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
            paramIndex);
        nameParam(data, name, paramIndex);
        final Class<? extends Param.Expander> expander = paramAnnotation.expander();
        if (expander != Param.ToStringExpander.class) {
          data.indexToExpanderClass().put(paramIndex, expander);
        }
        if (!data.template().hasRequestVariable(name)) {
          data.formParams().add(name);
        }
      });
      super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.queryMapIndex() == null,
            "QueryMap annotation was present on multiple parameters.");
        data.queryMapIndex(paramIndex);
      });
      super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.headerMapIndex() == null,
            "HeaderMap annotation was present on multiple parameters.");
        data.headerMapIndex(paramIndex);
      });
    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }
  }
}
