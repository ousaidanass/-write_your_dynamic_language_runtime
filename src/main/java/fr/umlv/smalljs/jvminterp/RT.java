package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.invoker;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;

import fr.umlv.smalljs.rt.ArrayMap;
import fr.umlv.smalljs.rt.ArrayMap.Layout;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
  private static final MethodHandle LOOKUP, REGISTER, TRUTH, GET_MH, METH_LOOKUP_MH, PARAMETER_COUNT_CHECK;
  static {
    var lookup = MethodHandles.lookup();
    try {
      LOOKUP = lookup.findVirtual(JSObject.class, "lookup", methodType(Object.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));
      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      GET_MH = lookup.findVirtual(JSObject.class, "getMethodHandle", methodType(MethodHandle.class));
      METH_LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
      PARAMETER_COUNT_CHECK = lookup.findStatic(RT.class, "parameterCountCheck", methodType(MethodHandle.class, MethodHandle.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
    return UNDEFINED;
  }

  public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
    return constant;
  }

  private static MethodHandle parameterCountCheck(MethodHandle mh, int parameterCount) {
    if (!mh.isVarargsCollector() && mh.type().parameterCount() != parameterCount) {
      throw new Failure("wrong number of parameters " + (parameterCount - 1) + " but should be " + (mh.type().parameterCount() - 1));
    }
    return mh;
  }
  /*
  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    //throw new UnsupportedOperationException("TODO bsm_funcall");
    // take GET_MH method handle
    var combiner = GET_MH;

    // check parameters count
    var parameterCheck = MethodHandles.insertArguments(PARAMETER_CHECK, 1, type.parameterCount() - 1);
    combiner = MethodHandles.filterReturnValue(combiner, parameterCheck);
    // make it accept an Object (not a JSObject) as first parameter
    combiner = combiner.asType(methodType(MethodHandle.class, Object.class));
    // create a generic invoker (MethodHandles.invoker()) on the parameter types without the qualifier
    var invoker = MethodHandles.invoker(type.dropParameterTypes(0, 1));
    // drop the qualifier
    invoker = MethodHandles.dropArguments(invoker, 0, Object.class);
    // use MethodHandles.foldArguments with GET_MH as combiner
    var target = MethodHandles.foldArguments(invoker, combiner);
    // create a constant callsite
    return new ConstantCallSite(target);
  }
*/
  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    return new InliningCache(type, 1);
  }

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, POINTER_CHECK, FALLBACK_PATH;
    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        POINTER_CHECK = lookup.findStatic(InliningCache.class, "pointerCheck", methodType(Boolean.class, Object.class, Object.class));
        FALLBACK_PATH = lookup.findVirtual(InliningCache.class, "fullbackPath", methodType(MethodHandle.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static final int MAX_DEPTH = 3;

    private final int depth;

    private final InliningCache root;

    public InliningCache(MethodType type, int depth) {
      this.depth = depth;
      super(type);
      this.root = this;
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
    }

    public InliningCache(MethodType type, int depth, InliningCache root) {
      this.depth = depth;
      this.root = root;
      super(type);
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
    }

    private static boolean pointerCheck(Object qualifier, JSObject expectedQualifier) {
      return qualifier == expectedQualifier;
    }

    private static MethodHandle fallbackPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject)qualifier;
      var mh = jsObject.getMethodHandle();
      parameterCheck(mh, type().parameterCount() - 1);
      return MethodHandles.dropArguments(mh, 0, Object.class).withVarargs(mh.isVarArgsCollector()).asType(type());
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
      var target = fallbackPath(qualifier, receiver);
      if (++depth == MAX_DEPTH) {
        root.setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), FALLBACK_PATH.bindTo(this)));
        return target;
      }
      var check = MethodHandles.insertArguments(POINTER_CHECK, 1, jsObject);
      var guard = MethodHandles.guardWithTest(
              check, target, new InliningCache(type()).dynamicInvoker()
      );
      setTarget(guard);
      return target;
    }
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String functionName) {
    throw new UnsupportedOperationException("TODO bsm_lookup");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    // get the LOOKUP method handle
    var target = LOOKUP;
    // use the global environment as first argument and the functionName as second argument
    target = MethodHandles.insertArguments();
    // create a constant callsite
    return new VolatileCallSite(target);
  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
    //throw new UnsupportedOperationException("TODO bsm_fun");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    var fun = classLoader.getDictionary().lookupAndClear(funId);
    return ByteCodeRewriter.createFunction(fun.optName().orElse("lambda"), fun.parameters(), fun.body(), globalEnv);
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
    // throw new UnsupportedOperationException("TODO bsm_register");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.getGlobal();
    //get the REGISTER method handle
    var target = REGISTER;
    // use the global environment as first argument and the functionName as second argument
    target = MethodHandles.insertArguments(target, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }
  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
    //throw new UnsupportedOperationException("TODO bsm_truth");
    // get the TRUTH method handle
    var target = TRUTH;
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_get");
    // get the LOOKUP method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
    throw new UnsupportedOperationException("TODO bsm_set");
    // get the REGISTER method handle
    // use the fieldName as second argument
    // make it accept an Object (not a JSObject) as first parameter
    // create a constant callsite
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject) receiver.lookup(fieldName);
    return function.getMethodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
    throw new UnsupportedOperationException("TODO bsm_methodcall");
    //var combiner = insertArguments(METH_LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
    //var target = foldArguments(invoker(type), combiner);
    //return new ConstantCallSite(target);
  }
}
