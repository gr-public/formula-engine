package com.novacloud.data.formula.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;

public abstract class ReflectUtil {
    private static final Logger log = LoggerFactory.getLogger(ReflectUtil.class);
    private static final String LENGTH = "length";
    private static final Map<Class<?>, Map<String, Method>> readerMap = newMap();
    private static final Map<Class<?>, Map<String, Method>> writerMap = newMap();
    private static final Map<Class<?>, Map<String, Type[]>> typeMap = newMap();
    private static final Map<Class<?>, Map<String, Field>> fieldMap = newMap();
    private static final Map<Class<?>, Constructor<?>> constructorMap = newMap();
    private static final Map<Class<? extends Enum<?>>, Enum<?>[]> enumMap = newMap();
    private static final Object initLock = new Object();

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> T newMap() {
        return (T) new HashMap();// WeakHashMap();
    }

    public static Map<String, Method> getGetterMap(final Class<?> clazz) {
        Map<String, Method> propertyMap = readerMap.get(clazz);
        if (propertyMap == null) {
            initProperties(clazz);
            propertyMap = readerMap.get(clazz);
        }
        return propertyMap;
    }

    public static Map<String, Method> getSetterMap(final Class<?> clazz) {
        Map<String, Method> propertyMap = writerMap.get(clazz);
        if (propertyMap == null) {
            initProperties(clazz);
            propertyMap = writerMap.get(clazz);
        }
        return propertyMap;
    }

    private static Map<String, Type[]> getTypeMap(final Class<?> clazz) {
        Map<String, Type[]> tMap = typeMap.get(clazz);
        if (tMap == null) {
            initProperties(clazz);
            tMap = typeMap.get(clazz);
        }
        return tMap;
    }

    public static Map<String, Field> getFieldMap(final Class<?> clazz) {
        Map<String, Field> fMap = fieldMap.get(clazz);
        if (fMap == null) {
            initProperties(clazz);
            fMap = fieldMap.get(clazz);
        }
        return fMap;
    }

    public static Set<String> getPropertySet(final Class<?> clazz) {
        return getTypeMap(clazz).keySet();
    }

    private static void initProperties(final Class<?> clazz) {
        synchronized (ReflectUtil.class) {
            try {
                Constructor<?> cons = clazz.getDeclaredConstructor();
                if (!cons.isAccessible()) {
                    cons.setAccessible(true);
                }
                constructorMap.put(clazz, cons);
            } catch (Exception ignored) {
                log.debug("",ignored);
            }
            HashMap<String, Method> getterMap = new HashMap<>();
            HashMap<String, Method> setterMap = new HashMap<>();
            HashMap<String, Type[]> propertyMap = new HashMap<>();
            HashMap<String, Field> fieldMap_ = new HashMap<>();
            try {
                if (!clazz.equals(Object.class)) {
                    getterMap.putAll(getGetterMap(clazz.getSuperclass()));
                    setterMap.putAll(getSetterMap(clazz.getSuperclass()));
                    propertyMap.putAll(getTypeMap(clazz.getSuperclass()));
                    fieldMap_.putAll(getFieldMap(clazz.getSuperclass()));
                }
                Method[] methods = clazz.getDeclaredMethods();

                for (Method m : methods) {
                    if ((m.getModifiers() & Modifier.PUBLIC) > 0) {
                        Class<?> type = m.getReturnType();
                        Class<?>[] params = m
                                .getParameterTypes();
                        String name = m.getName();
                        if (type == Void.TYPE) {
                            if (params.length == 1 && name.startsWith("set")) {
//                                type = params[0];
                                initMethod(setterMap, propertyMap, m,
                                        name.substring(3));
                            }
                        } else {
                            if (params.length == 0) {
                                if (name.startsWith("get")
                                        && !name.equals("getClass")) {
                                    initMethod(getterMap, propertyMap, m,
                                            name.substring(3));
                                } else if (type == Boolean.TYPE
                                        && name.startsWith("is")) {
                                    initMethod(getterMap, propertyMap, m,
                                            name.substring(2));
                                }
                            }
                        }
                    }
                }
                boolean nogs = propertyMap.isEmpty();
                boolean isMember = clazz.isMemberClass();

                Field[] fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);
                    String name = f.getName();
                    if (nogs
                            && (isMember || 0 < (f.getModifiers() & Modifier.PUBLIC))) {
                        propertyMap.put(name, new Type[]{f.getGenericType(),
                                clazz});
                    }
                    fieldMap_.put(name, f);
                }
            } catch (Exception e) {
                log.warn("初始化属性集合异常", e);
            } finally {
                readerMap.put(clazz, Collections.unmodifiableMap(getterMap));
                writerMap.put(clazz, Collections.unmodifiableMap(setterMap));
                typeMap.put(clazz, Collections.unmodifiableMap(propertyMap));
                fieldMap.put(clazz, Collections.unmodifiableMap(fieldMap_));
            }
        }
    }

    private static void initMethod(Map<String, Method> propertyMap,
                                   Map<String, Type[]> typeMap, Method m, String name) {
        if (name.length() > 0) {
            char c = name.charAt(0);
            if (Character.isUpperCase(c)) {
                name = Character.toLowerCase(c) + name.substring(1);
                m.setAccessible(true);
                propertyMap.put(name, m);
                Type type = m.getGenericReturnType();
                if (type == Void.TYPE) {
                    type = m.getParameterTypes()[0];
                }
                Type[] ot = typeMap.get(name);
                if (ot != null) {
                    if (ot[0] != type) {
                        log.warn("属性类型冲突：{} != {}" , ot[0], type);
                    }
                }
                typeMap.put(name, new Type[]{type, m.getDeclaringClass()});
            }
        }

    }

    @SuppressWarnings("unchecked")
    public static Map<String, ?> map(final Object context) {
        if (context == null) {
            return Collections.EMPTY_MAP;
        } else if (context instanceof Map) {
            return (Map<String, ?>) context;
        }
        return new ProxyMap(context, getTypeMap(context.getClass()).keySet());
    }

    private static int toIndex(Object key) {
        return key instanceof Number ? ((Number) key).intValue() : Integer
                .parseInt(String.valueOf(key));
    }

    public static Class<?> getValueType(Type type) {
        Type result = null;
        Class<?> clazz = null;
        if (type instanceof ParameterizedType) {
            clazz = (Class<?>) ((ParameterizedType) type).getRawType();
        } else {
            clazz = (Class<?>) type;
        }
        if (Collection.class.isAssignableFrom(clazz)) {
            result = getParameterizedType(type, Collection.class, 0);
        } else if (Map.class.isAssignableFrom(clazz)) {
            result = getParameterizedType(type, Map.class, 1);
        }
        if (result != null) {
            return baseClass(result);
        }
        return Object.class;
    }

    public static Class<?> getKeyType(Type type) {
        Class<?> clazz = null;
        if (type instanceof ParameterizedType) {
            clazz = (Class<?>) ((ParameterizedType) type).getRawType();
        } else {
            clazz = (Class<?>) type;
        }
        if (Map.class.isAssignableFrom(clazz)) {
            Type result = getParameterizedType(type, Map.class, 0);
            if (result != null) {
                return baseClass(result);
            }
        }
        return Integer.TYPE;
    }

    public static Type getParameterizedType(final Type ownerType,
                                            final Class<?> declaredClass, final Type declaredType) {
        if (declaredType instanceof TypeVariable) {
            String name = ((TypeVariable<?>) declaredType).getName();
            TypeVariable<?>[] typeVariables = declaredClass.getTypeParameters();
            if (typeVariables != null) {
                for (int i = 0; i < typeVariables.length; i++) {
                    if (name.equals(typeVariables[i].getName())) {
                        return getParameterizedType(ownerType, declaredClass, i);
                    }
                }
            }
            return declaredType;
        } else if (declaredType instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) declaredType;
            final Type[] types = parameterizedType.getActualTypeArguments();
            boolean changed = false;
            for (int i = 0; i < types.length; i++) {
                Type argumentType = types[i];
                Type trueType = getParameterizedType(ownerType, declaredClass, argumentType);
                if (!Objects.equals(argumentType , trueType)) {
                    types[i] = trueType;
                    changed = true;
                }
            }
            if (changed) {
                return changedParameterizedType(parameterizedType, types);
            }
        }
        // class
        // parameterizedType
        return declaredType;

    }

    private static Type changedParameterizedType(final ParameterizedType parameterizedType,
                                                 final Type[] changedTypes) {
        return new ParameterizedType() {
            public Type getRawType() {
                return parameterizedType.getRawType();
            }

            public Type getOwnerType() {
                return parameterizedType.getOwnerType();
            }

            public Type[] getActualTypeArguments() {
                return changedTypes;
            }
        };
    }

    public static Type getParameterizedType(final Type ownerType,
                                            final Class<?> declaredClass, int paramIndex) {
        Class<?> clazz = null;
        ParameterizedType pt = null;
        Type[] ats = null;
        TypeVariable<?>[] tps = null;
        if (ownerType instanceof ParameterizedType) {
            pt = (ParameterizedType) ownerType;
            clazz = (Class<?>) pt.getRawType();
            ats = pt.getActualTypeArguments();
            tps = clazz.getTypeParameters();
        } else {
            clazz = (Class<?>) ownerType;
        }
        if (Objects.equals(declaredClass , clazz)) {
            if (pt != null) {
                return pt.getActualTypeArguments()[paramIndex];
            }
            return Object.class;
        }
        Class<?>[] ifs = clazz.getInterfaces();
        for (int i = 0; i < ifs.length; i++) {
            Class<?> ifc = ifs[i];
            if (declaredClass.isAssignableFrom(ifc)) {
                return getTureType(
                        getParameterizedType(clazz.getGenericInterfaces()[i],
                                declaredClass, paramIndex), tps, ats);
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) {
            if (declaredClass.isAssignableFrom(superClass)) {
                return getTureType(
                        getParameterizedType(clazz.getGenericSuperclass(),
                                declaredClass, paramIndex), tps, ats);
            }
        }
        throw new IllegalArgumentException("查找真实类型失败:" + ownerType);
    }

    private static Type getTureType(Type type, TypeVariable<?>[] typeVariables,
                                    Type[] actualTypes) {
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> tv = (TypeVariable<?>) type;
            String name = tv.getName();
            if (actualTypes != null) {
                for (int i = 0; i < typeVariables.length; i++) {
                    if (name.equals(typeVariables[i].getName())) {
                        return actualTypes[i];
                    }
                }
            }
            return tv;
            // }else if (type instanceof Class<?>) {
            // return type;
        }
        return type;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> T newInstance(Class<T> clazz) {
        try {
            Constructor cons = constructorMap.get(clazz);
            if (cons != null) {
                initProperties(clazz);
                cons = constructorMap.get(clazz);
                if (cons != null) {
                    return (T) cons.newInstance();
                }
            }
            return clazz.newInstance();
        } catch (Exception ignored) {
            log.debug("",ignored);
            return null;
        }

    }

    public static Class<?> baseClass(Type result) {
        if (result instanceof Class<?>) {
            return (Class<?>) result;
        } else if (result instanceof ParameterizedType) {
            return baseClass(((ParameterizedType) result).getRawType());
        } else if (result instanceof WildcardType) {
            return baseClass(((WildcardType) result).getUpperBounds()[0]);
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Enum getEnum(Object value, Class clazz) {
        if (value instanceof String) {
            return Enum.valueOf(clazz, (String) value);
        } else if (value instanceof Number) {
            Enum[] es = enumMap.get(clazz);
            try {
                if (es == null) {
                    Method method = clazz.getMethod("values");
                    enumMap.put(clazz, es = (Enum[]) method.invoke(null));
                }

                int i = ((Number) value).intValue();
                return es[i];
            } catch (Exception ignored) {
                log.debug("",ignored);
            }
        }
        return null;
    }

    public static Class<?> getPropertyClass(Type type, Object key) {
        return baseClass(getPropertyType(type, key));
    }

    public static Type getPropertyType(Type type, Object key) {
        Class<?> clazz = baseClass(type);
        if (clazz != null) {
            if (Collection.class.isAssignableFrom(clazz)) {
                return getValueType(type);
            } else if (Map.class.isAssignableFrom(clazz)) {
                return getValueType(type);
            } else if (clazz.isArray()) {
                if (LENGTH.equals(key)) {
                    return Integer.TYPE;
                } else if (Number.class.isInstance(key)) {
                    return clazz.getComponentType();
                }
            } else {
                Type pd[] = getTypeMap(clazz).get(String.valueOf(key));
                if (pd != null) {
                    return getParameterizedType(type, (Class<?>) pd[1], pd[0]);
                }
            }
        }
        return null;
    }

    public static Object getValue(Object context, Object key) {
        if (context == null) {
            return null;
        }
        Class<?> clazz = context.getClass();
        try {
            if (context instanceof Map<?, ?>) {
                return ((Map<?, ?>) context).get(key);
            }
            if (key instanceof String) {
                Method method = getGetterMap(clazz).get(
                        key);
                if (method == null) {
                    Field field;
                    if (context instanceof Class<?>) {
                        field = getFieldMap((Class<?>) context).get(key);
                    } else {
                        field = getFieldMap(clazz).get(key);

                    }
                    if (field != null) {
                        return field.get(context);
                    }
                } else {
                    return method.invoke(context);
                }
                if (LENGTH.equals(key)) {
                    if (clazz.isArray()) {
                        return Array.getLength(context);
                    } else if (context instanceof Collection<?>) {
                        return ((Collection<?>) context).size();
                    } else if (context instanceof String) {
                        return ((String) context).length();
                    }
                }
                if (context instanceof Map<?, ?>) {
                    return ((Map<?, ?>) context).get(key);
                } else if (clazz.isArray()) {
                    return Array.getLength(context);
                } else {
                    return Array.get(context, toIndex(key));
                }
            } else if (context instanceof List<?>) {
                return ((List<?>) context).get(toIndex(key));
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("", e);
            }
        }
        return null;
    }

    public static void setValues(Object base, Map<String, Object> attributeMap) {
        for (Map.Entry<String, Object> entry : attributeMap.entrySet()) {
            ReflectUtil.setValue(base, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    public static void setValue(Object base, Object key, Object value) {
        if (base != null) {
            try {
                Class<?> clazz = base.getClass();
                if (clazz.isArray()) {
                    Array.set(base, toIndex(key), value);
                } else if (base instanceof List<?>) {
                    ((List<Object>) base).set(toIndex(key), value);
                }
                if (base instanceof Map) {
                    ((Map<Object, Object>) base).put(key, value);
                }
                String name = String.valueOf(key);
                Method method = getSetterMap(clazz).get(name);
                if (method != null) {
                    if (value != null) {
                        Class<?> type = method.getParameterTypes()[0];
                        value = toWrapper(value, type);
                    }
                    method.invoke(base, value);
                } else {
                    Field field = fieldMap.get(clazz).get(name);
                    if (value != null) {
                        Class<?> type = field.getType();
                        value = toWrapper(value, type);
                    }
                    field.set(base, value);
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("", e);
                }
            }
        }

    }

    private static Object toWrapper(Object value, Class<?> type) {
        if (!type.isInstance(value)) {
            type = toWrapper(type);
            if (Number.class.isAssignableFrom(type)) {
                value = toValue((Number) value, type);
            }
        }
        return value;
    }

    public static Number toValue(Number value, Class<?> type) {
        if (type == Long.class) {
            return value.longValue();
        } else if (type == Integer.class) {
            return value.intValue();
        } else if (type == Short.class) {
            return value.shortValue();
        } else if (type == Byte.class) {
            return value.byteValue();
        } else if (type == Double.class) {
            return value.doubleValue();
        } else if (type == Float.class) {
            return value.floatValue();
        } else {
            Class<?> clazz = ReflectUtil.toWrapper(type);
            if (Objects.equals(clazz , type)) {
                return null;
            } else {
                return toValue(value, clazz);
            }
        }
    }

    public static Class<?> toWrapper(
            Class<?> type) {
        if (type.isPrimitive()) {
            if (Byte.TYPE == type) {
                return Byte.class;
            } else if (Short.TYPE == type) {
                return Short.class;
            } else if (Integer.TYPE == type) {
                return Integer.class;
            } else if (Long.TYPE == type) {
                return Long.class;
            } else if (Float.TYPE == type) {
                return Float.class;
            } else if (Double.TYPE == type) {
                return Double.class;
            } else if (Character.TYPE == type) {
                return Character.class;
            } else if (Boolean.TYPE == type) {
                return Boolean.class;
            }
        }
        return type;
    }
}
