package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.util.stream.Collectors.joining;

public final class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> instrs, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Block");
        // TODO loop over all instructions
        for (var instr: instrs) {
          visit(instr, env);
        }
        yield UNDEFINED;
      }
      case Literal<?>(Object value, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO Literal");
        yield value;
      }
      case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO FunCall");
        var value = visit(qualifier, env);
        var function = asJSObject(value, lineNumber);
        var values = args.stream().map(arg -> visit(arg, env)).toArray();
        yield function.invoke(UNDEFINED, values);
      }
      case LocalVarAccess(String name, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO LocalVarAccess");
        yield env.lookup(name);
      }
      case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO LocalVarAssignment");
        if (declaration && env.lookup(name) != UNDEFINED) {
          throw new Failure("var " + name + " already defined at " + lineNumber);
        }
        var value = visit(expr, env);
        env.register(name, value);
        yield value;
      }
      case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Fun");
        var functionName = optName.orElse("lambda");
        JSObject.Invoker invoker = new JSObject.Invoker() {
          @Override
          public Object invoke(Object receiver, Object... args) {
            // check the arguments length
            if (args.length != parameters.size()) {
              throw new Failure("the number of arguments is different from the number of parameters");
            }
            // create a new environment
            var newEnv = JSObject.newEnv(env);

            // add this and all the parameters
            newEnv.register("this", receiver);
            for (int i = 0; i < parameters.size(); i++) {
              newEnv.register(parameters.get(i), args[i]);
            }

            // visit the body
            try {
              return visit(body, newEnv);
            } catch (ReturnError error) {
              return error.getValue();
            }
          }
        };
         //create the JS function with the invoker
        var function = JSObject.newFunction(functionName, invoker);
         //register it if necessary
        optName.ifPresent(name -> env.register(name, function));
         yield function;
      }
      case Return(Expr expr, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO Return");
        var value = visit(expr, env);
        throw new ReturnError(value);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO If");
        var value = visit(condition, env);
        if (value instanceof Integer i && i == 1) {
          visit(trueBlock, env);
        } else {
          visit(falseBlock, env);
        }
        yield UNDEFINED;
      }
      case New(Map<String, Expr> initMap, int lineNumber) -> {
				//throw new UnsupportedOperationException("TODO New");
        var object = JSObject.newObject(null);
        initMap.forEach((key, expr) -> {
          var value = visit(expr, env);
          object.register(key, value);
        });
        yield object;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO FieldAccess");
        var value = visit(receiver, env);
        var jsObject = asJSObject(value, lineNumber);
        yield jsObject.lookup(name);
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO FieldAssignment");
        var value = visit(receiver, env);
        var jsObject = asJSObject(value, lineNumber);
        var value2 = visit(expr, env);
        jsObject.register(name, value2);
        yield value2; // car: b = x.a = 2
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        //throw new UnsupportedOperationException("TODO MethodCall");
        var value = visit(receiver, env);
        var jsObject = asJSObject(value, lineNumber);
        var fun = jsObject.lookup(name);
        var function = asJSObject(fun, lineNumber);
        var values = args.stream().map(arg -> visit(arg, env)).toArray();
        yield function.invoke(jsObject, values);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static JSObject createGlobalEnv(PrintStream outStream) {
    JSObject globalEnv = JSObject.newEnv(null);
    globalEnv.register("global", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (_, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (_, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (_, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (_, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (_, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (_, args) -> (Integer) args[0] % (Integer) args[1]));
    globalEnv.register("==", JSObject.newFunction("==", (_, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (_, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    return globalEnv;
  }

  public static void interpret(Script script, PrintStream outStream) {
    JSObject globalEnv =createGlobalEnv(outStream);
    Block body = script.body();
    visit(body, globalEnv);
  }
}

