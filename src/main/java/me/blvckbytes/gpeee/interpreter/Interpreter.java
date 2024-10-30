/*
 * MIT License
 *
 * Copyright (c) 2022 BlvckBytes
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.blvckbytes.gpeee.interpreter;

import me.blvckbytes.gpeee.Tuple;
import me.blvckbytes.gpeee.error.*;
import me.blvckbytes.gpeee.functions.ExpressionFunctionArgument;
import me.blvckbytes.gpeee.logging.DebugLogSource;
import me.blvckbytes.gpeee.functions.AExpressionFunction;
import me.blvckbytes.gpeee.functions.IStandardFunctionRegistry;
import me.blvckbytes.gpeee.parser.ComparisonOperation;
import me.blvckbytes.gpeee.parser.EqualityOperation;
import me.blvckbytes.gpeee.parser.MathOperation;
import me.blvckbytes.gpeee.parser.expression.*;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Interpreter {

  private final Logger logger;
  private final IStandardFunctionRegistry standardFunctionRegistry;

  public Interpreter(Logger logger, IStandardFunctionRegistry standardFunctionRegistry) {
    this.logger = logger;
    this.standardFunctionRegistry = standardFunctionRegistry;
  }

  public Object evaluateExpression(AExpression expression, IEvaluationEnvironment environment) throws AEvaluatorError {
    if (expression == null)
      return null;

    // Every expression evaluation starts out with a fresh interpretation environment
    // State is NOT kept between evaluation sessions
    return evaluateExpressionSub(expression, environment, new InterpretationEnvironment());
  }

  public Object evaluateExpressionSub(
    AExpression expression,
    IEvaluationEnvironment evaluationEnvironment,
    InterpretationEnvironment interpretationEnvironment
  ) throws AEvaluatorError {

    if (expression == null)
      return null;

    logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating " + expression.getClass().getSimpleName() + ": " + expression.expressionify());

    IValueInterpreter valueInterpreter = evaluationEnvironment.getValueInterpreter();

    //////////////////////// Entry Point ////////////////////////

      switch (expression) {
          case ProgramExpression program -> {

              Object lastValue = null;
              for (int i = 0; i < program.getLines().size(); i++) {
                  int programLine = i;
                  AExpression line = program.getLines().get(programLine);

                  logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Processing program line " + (programLine + 1));

                  lastValue = evaluateExpressionSub(line, evaluationEnvironment, interpretationEnvironment);
              }

              // The return value of a program is the return value of it's last line
              return lastValue;
          }


          /////////////////////// Static Values ///////////////////////
          case LongExpression longExpression -> {
              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Taking the immediate long value");
              return longExpression.getNumber();
          }
          case DoubleExpression doubleExpression -> {
              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Taking the immediate double value");
              return doubleExpression.getValue();
          }
          case LiteralExpression literalExpression -> {
              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Taking the immediate literal value");
              return literalExpression.getValue();
          }
          case StringExpression stringExpression -> {
              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Taking the immediate string value");
              return valueInterpreter.asString(stringExpression.getValue());
          }


          ////////////////////// Variable Values //////////////////////
          case IdentifierExpression identifierExpression -> {
              return lookupVariable(evaluationEnvironment, interpretationEnvironment, identifierExpression);
          }

          ///////////////////////// Functions /////////////////////////
          case FunctionInvocationExpression functionExpression -> {
              AExpressionFunction function = lookupFunction(evaluationEnvironment, interpretationEnvironment, functionExpression.getName());

              // Function does not exist within the current environment
              if (function == null) {

                  // Was an optional call, respond with null
                  if (functionExpression.isOptional()) {
                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Function " + functionExpression.getName().getSymbol() + " not found, returning null (optional call)");
                      return null;
                  }

                  throw new UndefinedFunctionError(functionExpression.getName());
              }

              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating arguments of function invocation " + functionExpression.getName().getSymbol());

              @Nullable List<ExpressionFunctionArgument> argDefinitions = function.getArguments();

              List<Object> arguments = new ArrayList<>();

              // Argument definitions are available, fill up the argument list
              // with null values to match the number of requested arguments
              if (argDefinitions != null) {
                  while (arguments.size() < argDefinitions.size())
                      arguments.add(null);
              }

              boolean encounteredNamedArgument = false;
              int debugArgCounter = 0, nonNamedArgCounter = 0;

              // Evaluate and collect all arguments
              for (Tuple<AExpression, @Nullable IdentifierExpression> argument : functionExpression.getArguments()) {
                  int debugArgIndex = ++debugArgCounter;
                  logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating argument " + debugArgIndex);

                  Object argumentValue = evaluateExpressionSub(argument.a, evaluationEnvironment, interpretationEnvironment);

                  // Argument definitions are available and this argument has a name attached
                  if (argDefinitions != null && argument.b != null) {
                      encounteredNamedArgument = true;

                      // Look through all definitions to find a match
                      boolean foundMatch = false;
                      for (int i = 0; i < argDefinitions.size(); i++) {
                          int argIndex = i;
                          ExpressionFunctionArgument argDefinition = argDefinitions.get(argIndex);
                          String argName = argument.b.getSymbol();

                          // Argument's identifier is not matching the arg definition name
                          if (!argDefinition.getName().equalsIgnoreCase(argName))
                              continue;

                          // Found a name match, set the value at that same index
                          logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Matched named argument " + argName + " to index " + argIndex);
                          arguments.set(i, argumentValue);
                          foundMatch = true;
                          break;
                      }

                      // This argument is mapped, continue
                      if (foundMatch)
                          continue;

                      // Could not find a match for this named argument
                      throw new UndefinedFunctionArgumentNameError(function, argument.b);
                  }

                  // Encountered a non-named argument after encountering a named argument
                  if (encounteredNamedArgument)
                      throw new NonNamedFunctionArgumentError(argument.a);

                  // No definitions provided, just add to the list (variadic of unchecked type)
                  if (argDefinitions == null) {

                      // If there are no definitions provided by the function, named arguments should throw
                      // as they cannot be possibly matched with anything and should thus be omitted
                      IdentifierExpression argNameExpression = argument.b;
                      if (argNameExpression != null)
                          throw new UndefinedFunctionArgumentNameError(function, argNameExpression);

                      arguments.add(argumentValue);
                  }

                  // Set at the next non-named index (before named can occur)
                  else if (nonNamedArgCounter < arguments.size())
                      arguments.set(nonNamedArgCounter++, argumentValue);
              }

              // Let the function validate the arguments of it's invocation before actually performing the call
              function.validateArguments(functionExpression, evaluationEnvironment.getValueInterpreter(), arguments);

              // Invoke and return that function's result
              Object result = function.apply(evaluationEnvironment, arguments);

              // Throw an exception based on the error description object, now that the expression ref is available
              if (result instanceof FunctionInvocationError error) {
                  int index = error.getArgumentIndex();
                  Object value = arguments.size() > index ? arguments.get(index) : null;
                  throw new InvalidFunctionInvocationError(functionExpression, index, value, error.getMessage());
              }

              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Invoked function, result: " + result);
              return result;
          }
          case CallbackExpression callbackExpression -> {

              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Setting up the java endpoint for a callback expression");

              // This lambda function will be called by java every time the callback is invoked
              return new AExpressionFunction() {
                  @Override
                  public Object apply(IEvaluationEnvironment environment, List<@Nullable Object> args) {
                      // Copy the static variable table and extend it below
                      Map<String, Object> combinedVariables = new HashMap<>(environment.getStaticVariables());

                      // Map all identifiers from the callback's signature to a matching java argument in sequence
                      // If there are more arguments in the signature than provided by java, they'll just be set to null
                      for (int i = 0; i < callbackExpression.getSignature().size(); i++) {
                          String variableIdentifier = callbackExpression.getSignature().get(i).getSymbol();
                          Object variableValue = i < args.size() ? args.get(i) : null;

                          logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Adding " + variableIdentifier + "=" + variableValue + " to a callback's environment");
                          combinedVariables.put(variableIdentifier, variableValue);
                      }

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating a callback's body");

                      // Callback expressions are evaluated within their own environment, which extends the current environment
                      // by the additional variables coming from the arguments passed by the callback caller
                      Object result = evaluateExpressionSub(callbackExpression.getBody(), new IEvaluationEnvironment() {

                          @Override
                          public Map<String, AExpressionFunction> getFunctions() {
                              return environment.getFunctions();
                          }

                          @Override
                          public Map<String, Supplier<?>> getLiveVariables() {
                              return environment.getLiveVariables();
                          }

                          @Override
                          public Map<String, ?> getStaticVariables() {
                              return combinedVariables;
                          }

                          @Override
                          public IValueInterpreter getValueInterpreter() {
                              return environment.getValueInterpreter();
                          }
                      }, interpretationEnvironment);

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Callback result=" + result);
                      return result;
                  }

                  @Override
                  public @Nullable List<ExpressionFunctionArgument> getArguments() {
                      return null;
                  }
              };
          }


          /////////////////////// Control Flow ////////////////////////
          case IfThenElseExpression ifExpression -> {

              // Evaluate the if statement's condition expression
              Object condition = evaluateExpressionSub(ifExpression.getCondition(), evaluationEnvironment, interpretationEnvironment);

              // Interpret the result as a boolean and evaluate the body accordingly
              if (evaluationEnvironment.getValueInterpreter().asBoolean(condition))
                  return evaluateExpressionSub(ifExpression.getPositiveBody(), evaluationEnvironment, interpretationEnvironment);

              return evaluateExpressionSub(ifExpression.getNegativeBody(), evaluationEnvironment, interpretationEnvironment);
          }


          /////////////////////// Member Access ////////////////////////
          case MemberAccessExpression memberExpression -> {

              // Look up the container's value
              Object value = evaluateExpressionSub(memberExpression.getLhs(), evaluationEnvironment, interpretationEnvironment);
              AExpression access = memberExpression.getRhs();

              String fieldName;

              // Already an identifier, use it's symbol
              if (access instanceof IdentifierExpression)
                  fieldName = ((IdentifierExpression) access).getSymbol();

                  // Evaluate the name expression as a string
              else
                  fieldName = valueInterpreter.asString(evaluateExpressionSub(access, evaluationEnvironment, interpretationEnvironment));

              // Cannot access any members of null
              if (value == null) {

                  // Optional access, respond with null
                  if (memberExpression.isOptional())
                      return null;

                  throw new UnknownMemberError(memberExpression, null, fieldName);
              }

              // Look through all available fields within the container
              for (Field f : value.getClass().getDeclaredFields()) {
                  // Not the target field
                  if (!f.getName().equalsIgnoreCase(fieldName))
                      continue;

                  try {
                      f.setAccessible(true);
                      return f.get(value);
                  } catch (Exception e) {
                      logger.log(Level.SEVERE, e, () -> "Could not access an object's member");
                      return "<error>";
                  }
              }

              // Optional access, respond with null
              if (memberExpression.isOptional())
                  return null;

              // Found no field with the required name
              throw new UnknownMemberError(memberExpression, value, fieldName);
          }


          //////////////////// Binary Expressions /////////////////////
          case ABinaryExpression aBinaryExpression -> {
              logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating LHS and RHS of a binary expression");

              Object rhs = evaluateExpressionSub(aBinaryExpression.getRhs(), evaluationEnvironment, interpretationEnvironment);

              // Try to execute an assignment expression before evaluating the LHS value
              // of the binary expression, which would end up in a variable lookup
              if (expression instanceof AssignmentExpression) {
                  String identifier = ((IdentifierExpression) ((ABinaryExpression) expression).getLhs()).getSymbol();
                  boolean isFunction = rhs instanceof AExpressionFunction;

                  // Is not a function, check for existing variable names before adding
                  if (!isFunction) {
                      if (
                              evaluationEnvironment.getLiveVariables().containsKey(identifier) ||
                                      evaluationEnvironment.getStaticVariables().containsKey(identifier) ||
                                      interpretationEnvironment.getVariables().containsKey(identifier)
                      ) {
                          throw new IdentifierInUseError((IdentifierExpression) ((AssignmentExpression) expression).getLhs());
                      }

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Storing variable " + identifier + " within the interpretation environment");

                      interpretationEnvironment.getVariables().put(identifier, rhs);
                  }

                  // Is a function, check for existing function names before adding
                  else {
                      if (
                              standardFunctionRegistry.lookup(identifier) != null ||
                                      evaluationEnvironment.getFunctions().containsKey(identifier) ||
                                      interpretationEnvironment.getFunctions().containsKey(identifier)
                      ) {
                          throw new IdentifierInUseError((IdentifierExpression) ((AssignmentExpression) expression).getLhs());
                      }

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Storing function " + identifier + " within the interpretation environment");

                      interpretationEnvironment.getFunctions().put(identifier, (AExpressionFunction) rhs);
                  }

                  // Assignments always return their assigned type
                  return rhs;
              }

              Object lhs = evaluateExpressionSub(aBinaryExpression.getLhs(), evaluationEnvironment, interpretationEnvironment);

              switch (expression) {
                  case MathExpression mathExpression -> {
                      MathOperation operation = mathExpression.getOperation();
                      Object result;

                      result = valueInterpreter.performMath(lhs, rhs, operation);
                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Math Operation operation " + operation + " result: " + result);
                      return result;
                  }
                  case NullCoalesceExpression nullCoalesce -> {
                      Object inputValue = evaluateExpressionSub(nullCoalesce.getLhs(), evaluationEnvironment, interpretationEnvironment);

                      // Input value is non-null, return that
                      if (inputValue != null)
                          return inputValue;

                      // Return the provided fallback value
                      return evaluateExpressionSub(nullCoalesce.getRhs(), evaluationEnvironment, interpretationEnvironment);
                  }
                  case EqualityExpression equalityExpression -> {
                      EqualityOperation operation = equalityExpression.getOperation();
                      boolean result = switch (operation) {
                          case EQUAL -> valueInterpreter.areEqual(lhs, rhs, false);
                          case NOT_EQUAL -> !valueInterpreter.areEqual(lhs, rhs, false);
                          case EQUAL_EXACT -> valueInterpreter.areEqual(lhs, rhs, true);
                          case NOT_EQUAL_EXACT -> !valueInterpreter.areEqual(lhs, rhs, true);
                      };

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Equality Operation operation " + operation + " result: " + result);
                      return result;
                  }
                  case ComparisonExpression comparisonExpression -> {
                      ComparisonOperation operation = comparisonExpression.getOperation();
                      int comparisonResult = valueInterpreter.compare(lhs, rhs);
                      boolean result = switch (operation) {
                          case LESS_THAN -> comparisonResult < 0;
                          case GREATER_THAN -> comparisonResult > 0;
                          case LESS_THAN_OR_EQUAL -> comparisonResult <= 0;
                          case GREATER_THAN_OR_EQUAL -> comparisonResult >= 0;
                          default -> false;
                      };

                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Comparison Operation operation " + operation + " result: " + result);
                      return result;
                  }
                  case ConjunctionExpression conjunctionExpression -> {
                      boolean result = valueInterpreter.asBoolean(lhs) && valueInterpreter.asBoolean(rhs);
                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Conjunction Operation result: " + result);
                      return result;
                  }
                  case DisjunctionExpression disjunctionExpression -> {
                      boolean result = valueInterpreter.asBoolean(lhs) || valueInterpreter.asBoolean(rhs);
                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Disjunction Operation result: " + result);
                      return result;
                  }
                  case ConcatenationExpression concatenationExpression -> {
                      String result = valueInterpreter.asString(lhs) + valueInterpreter.asString(rhs);
                      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Concatenation Operation result: " + result);
                      return result;
                  }
                  case IndexExpression indexExpression -> {

                      if (lhs instanceof List<?> list) {
                          int key = (int) valueInterpreter.asLong(rhs);
                          int listLength = list.size();

                          // Not a valid list index
                          if (key >= listLength) {

                              // Index is optional, respond with null
                              if (indexExpression.isOptional())
                                  return null;

                              throw new InvalidIndexError(indexExpression, key, listLength);
                          }

                          Object result = list.get(key);

                          logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Indexing a list at " + key + ": " + result);

                          return result;
                      }

                      if (lhs != null && lhs.getClass().isArray()) {
                          int key = (int) valueInterpreter.asLong(rhs);
                          int arrayLength = Array.getLength(lhs);

                          // Not a valid array index
                          if (key >= arrayLength) {

                              // Index is optional, respond with null
                              if (indexExpression.isOptional())
                                  return null;

                              throw new InvalidIndexError(indexExpression, key, arrayLength);
                          }

                          Object result = Array.get(lhs, key);

                          logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Indexing an array at " + key + ": " + result);

                          return result;
                      }

                      if (lhs instanceof Map<?, ?> map) {
                          String key = valueInterpreter.asString(rhs);

                          // Not a valid map member
                          if (!map.containsKey(key)) {

                              // Index is optional, respond with null
                              if (indexExpression.isOptional())
                                  return null;

                              throw new InvalidMapKeyError(indexExpression, key);
                          }

                          Object result = map.get(key);

                          logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Indexing a map at " + key + ": " + result);

                          return result;
                      }

                      // Cannot index this type of value
                      throw new NonIndexableValueError(indexExpression, lhs);
                  }
                  default -> {
                  }
              }
          }
          default -> {
          }
      }

      ///////////////////// Unary Expressions /////////////////////

    if (expression instanceof AUnaryExpression) {
      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Evaluating input of a unary expression");

      Object input = evaluateExpressionSub(((AUnaryExpression) expression).getInput(), evaluationEnvironment, interpretationEnvironment);

      if (expression instanceof FlipSignExpression) {
        Object result;

        if (valueInterpreter.hasDecimalPoint(input))
          result = -1 * valueInterpreter.asDouble(input);
        else
          result = -1 * valueInterpreter.asLong(input);

        logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Flip Sign Operation result: " + result);
        return result;
      }

      if (expression instanceof InvertExpression) {
        boolean result = !valueInterpreter.asBoolean(input);
        logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Invert Operation result: " + result);
        return result;
      }
    }

    throw new IllegalStateException("Cannot parse unknown expression type " + expression.getClass());
  }

  /**
   * Tries to look up a function within the provided environments based on an identifier
   * @param evaluationEnvironment Evaluation environment to look in
   * @param interpretationEnvironment Interpretation environment to look in
   * @param identifier Identifier to look up
   * @return Function value
   */
  private @Nullable AExpressionFunction lookupFunction(
    IEvaluationEnvironment evaluationEnvironment,
    InterpretationEnvironment interpretationEnvironment,
    IdentifierExpression identifier
  ) {
    String symbol = identifier.getSymbol().toLowerCase(Locale.ROOT);

    logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Looking up function " + symbol);

    AExpressionFunction stdFunction = standardFunctionRegistry.lookup(symbol);
    if (stdFunction != null) {
      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved standard function");
      return stdFunction;
    }

    if (evaluationEnvironment.getFunctions().containsKey(symbol)) {
      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved environment function");
      return evaluationEnvironment.getFunctions().get(symbol);
    }

    if (interpretationEnvironment.getFunctions().containsKey(symbol)) {
      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved interpretation function");
      return interpretationEnvironment.getFunctions().get(symbol);
    }

    return null;
  }

  /**
   * Tries to look up a variable within the provided environments based on an identifier
   * @param evaluationEnvironment Evaluation environment to look in
   * @param interpretationEnvironment Interpretation environment to look in
   * @param identifier Identifier to look up
   * @return Variable value
   * @throws UndefinedVariableError A variable with that identifier does not exist within the environments
   */
  private Object lookupVariable(
    IEvaluationEnvironment evaluationEnvironment,
    InterpretationEnvironment interpretationEnvironment,
    IdentifierExpression identifier
  ) throws UndefinedVariableError {
    String symbol = identifier.getSymbol().toLowerCase(Locale.ROOT);

    logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Looking up variable " + symbol);

    if (evaluationEnvironment.getStaticVariables().containsKey(symbol)) {
      Object value = evaluationEnvironment.getStaticVariables().get(symbol);

      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved static variable value: " + value);

      return value;
    }

    Supplier<?> valueSupplier = evaluationEnvironment.getLiveVariables().get(symbol);
    if (valueSupplier != null) {
      Object value = valueSupplier.get();

      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved dynamic variable value: " + value);

      return value;
    }

    if (interpretationEnvironment.getVariables().containsKey(symbol)) {
      Object value = interpretationEnvironment.getVariables().get(symbol);

      logger.log(Level.FINEST, () -> DebugLogSource.INTERPRETER + "Resolved interpretation environment variable value: " + value);

      return value;
    }

    throw new UndefinedVariableError(identifier);
  }
}