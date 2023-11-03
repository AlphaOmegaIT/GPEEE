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

package me.blvckbytes.gpeee.parser;

import me.blvckbytes.gpeee.Tuple;
import me.blvckbytes.gpeee.logging.DebugLogSource;
import me.blvckbytes.gpeee.error.AEvaluatorError;
import me.blvckbytes.gpeee.error.UnexpectedTokenError;
import me.blvckbytes.gpeee.parser.expression.*;
import me.blvckbytes.gpeee.tokenizer.ITokenizer;
import me.blvckbytes.gpeee.tokenizer.Token;
import me.blvckbytes.gpeee.tokenizer.TokenType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This parser uses the compact and flexible algorithm called "precedence climbing" / "top down
 * recursive decent", which of course is not highly efficient. The main purpose of this project is
 * to parse expressions within configuration files once and then just evaluate the AST within the
 * desired evaluation context at runtime over and over again. Due to the ahead-of-time nature of
 * this intended use-case, efficiency at the level of the parser is sacrificed for understandability.
 */
public class Parser {

  private final Logger logger;
  private final FExpressionParser[] precedenceLadder;

  public Parser(Logger logger) {
    this.logger = logger;

    this.precedenceLadder = new FExpressionParser[] {
      this::parseAssignmentExpression,
      this::parseNullCoalesceExpression,
      this::parseConcatenationExpression,
      this::parseDisjunctionExpression,
      this::parseConjunctionExpression,
      this::parseEqualityExpression,
      this::parseComparisonExpression,
      this::parseAdditiveExpression,
      this::parseMultiplicativeExpression,
      this::parseExponentiationExpression,
      this::parseNegationExpression,
      this::parseFlipSignExpression,
      this::parseIndexExpression,
      this::parseMemberAccessExpression,
      this::parseFunctionInvocationExpression,
      this::parseIfThenElseExpression,
      this::parseCallbackExpression,
      this::parseParenthesisExpression,
      (tk, s) -> this.parsePrimaryExpression(tk),
    };
  }

  /**
   * Parses all available tokens into an abstract syntax tree (AST)
   * @return AST root ready for execution
   */
  public ProgramExpression parse(ITokenizer tokenizer) throws AEvaluatorError {
    List<AExpression> lines = new ArrayList<>();

    while (tokenizer.peekToken() != null) {
      // Start to parse the lowest precedence expression and climb up
      lines.add(invokeLowestPrecedenceParser(tokenizer));
    }

    // Completely empty expression, should've at least parsed one line
    if (lines.size() == 0)
      throw new UnexpectedTokenError(tokenizer, null, TokenType.valuesInTrialOrder);

    return new ProgramExpression(
      lines, lines.get(0).getHead(), lines.get(lines.size() - 1).getTail(),
      tokenizer.getRawText()
    );
  }

  //=========================================================================//
  //                            Expression Parsers                           //
  //=========================================================================//

  /////////////////////// Complex Expressions ///////////////////////

  private AExpression parseIfThenElseExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a if then else expression");

    Token tk = tokenizer.peekToken();

    // There's no if keyword as the next token
    if (tk == null || tk.getType() != TokenType.KW_IF) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a if then else expression");
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Consume if keyword
    Token head = tokenizer.consumeToken();

    // Parse condition
    AExpression condition = invokeLowestPrecedenceParser(tokenizer);

    // Has to be followed by the then keyword
    if ((tk = tokenizer.consumeToken()) == null || tk.getType() != TokenType.KW_THEN)
      throw new UnexpectedTokenError(tokenizer, tk, TokenType.KW_ELSE);

    // Parse positive body expression
    AExpression positiveBody = invokeLowestPrecedenceParser(tokenizer);

    // Has to be followed by the else keyword
    if ((tk = tokenizer.consumeToken()) == null || tk.getType() != TokenType.KW_ELSE)
      throw new UnexpectedTokenError(tokenizer, tk, TokenType.KW_ELSE);

    // Parse negative body expression
    AExpression negativeBody = invokeLowestPrecedenceParser(tokenizer);

    return new IfThenElseExpression(condition, positiveBody, negativeBody, head, tk, tokenizer.getRawText());
  }

  private AExpression parseCallbackExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a callback expression");

    Token tk = tokenizer.previousToken();

    // This is not a callback expression, but rather part of a member access
    // chain, as the previous token was a dot
    if (tk != null && tk.getType() == TokenType.DOT) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a callback expression");
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    tk = tokenizer.peekToken();

    // There's no opening parenthesis as the next token
    if (tk == null || tk.getType() != TokenType.PARENTHESIS_OPEN) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a callback expression");
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Save once before consuming anything
    tokenizer.saveState(true);

    // Consume the opening parenthesis
    Token head = tokenizer.consumeToken();

    List<IdentifierExpression> signature = new ArrayList<>();

    // As long as there is no closing parenthesis, there are still arguments left
    while ((tk = tokenizer.peekToken()) != null && tk.getType() != TokenType.PARENTHESIS_CLOSE) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Parsing argument " + signature.size());

      if (signature.size() > 0) {
        // Arguments other than the first one need to be separated out by a comma
        // If there's no comma, this cannot be a callback expression and is more likely a parenthesis expression
        if (tk.getType() != TokenType.COMMA) {
          logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a callback expression");
          tokenizer.restoreState(true);
          return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
        }

        // Consume that comma
        tokenizer.consumeToken();
      }

      AExpression identifier;
      try {
        identifier = parsePrimaryExpression(tokenizer);
      }

      // This expression clashed with parentheses expressions
      // Some day, I'm sure I'll revisit this quick and dirty solution
      // Checking for identifiers, if there was no identifier, it's not a callback expression
      catch (UnexpectedTokenError e) {
        identifier = null;
      }

      // Anything else than an identifier cannot be within a callback's parentheses
      if (!(identifier instanceof IdentifierExpression)) {
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a callback expression");
        tokenizer.restoreState(true);
        return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
      }

      signature.add((IdentifierExpression) identifier);
    }

    // Callback signatures have to be terminated with a closing parenthesis
    tk = tokenizer.consumeToken();
    if (tk == null || tk.getType() != TokenType.PARENTHESIS_CLOSE)
      throw new UnexpectedTokenError(tokenizer, tk, TokenType.PARENTHESIS_CLOSE);

    // Expect and consume the arrow operator
    tk = tokenizer.consumeToken();
    if (tk == null || tk.getType() != TokenType.ARROW)
      throw new UnexpectedTokenError(tokenizer, tk, TokenType.ARROW);

    AExpression body = invokeLowestPrecedenceParser(tokenizer);

    return new CallbackExpression(signature, body, head, body.getTail(), tokenizer.getRawText());
  }

  private AExpression parseFunctionInvocationExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a function invocation expression");

    Token tk = tokenizer.peekToken();

    // There's no identifier or minus as the next token
    if (tk == null || tk.getType() != TokenType.IDENTIFIER) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a function invocation expression");
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Store before consuming the identifier
    tokenizer.saveState(true);

    // Consume the identifier
    Token tokenIdentifier = tk;
    tokenizer.consumeToken();

    tk = tokenizer.peekToken();

    // There's no opening parenthesis as the next token
    if (tk == null || (tk.getType() != TokenType.PARENTHESIS_OPEN && tk.getType() != TokenType.OPTIONAL_PARENTHESIS_OPEN)) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a function invocation expression");

      // Put back the token
      tokenizer.restoreState(true);
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Not going to go need to restore anymore, this has to be a function invocation
    tokenizer.discardState(true);

    // Consume the opening parenthesis
    Token paren = tk;
    tokenizer.consumeToken();

    List<Tuple<AExpression, @Nullable IdentifierExpression>> arguments = new ArrayList<>();

    // As long as there is no closing parenthesis, there are still arguments left
    while ((tk = tokenizer.peekToken()) != null && tk.getType() != TokenType.PARENTHESIS_CLOSE) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Parsing argument " + arguments.size());

      if (arguments.size() > 0) {
        // Arguments other than the first one need to be separated out by a comma
        if (tk.getType() != TokenType.COMMA)
          throw new UnexpectedTokenError(tokenizer, tk, TokenType.COMMA);

        // Consume that comma
        tokenizer.consumeToken();
      }

      Token identifier = null;
      if ((tk = tokenizer.peekToken()) != null && tk.getType() == TokenType.IDENTIFIER) {
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a named argument");

        // Save before consuming so the next token can be peeked too
        tokenizer.saveState(true);
        identifier = tokenizer.consumeToken();

        // There's no assign token following this identifier, it cannot be a named argument
        if ((tk = tokenizer.peekToken()) == null || tk.getType() != TokenType.ASSIGN) {
          logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not a named argument");

          // Put the identifier back
          tokenizer.restoreState(true);
          identifier = null;
        }

        // Is a named argument
        else {
          // Don't need to revert anymore
          tokenizer.discardState(true);

          // Consume the assign token
          tokenizer.consumeToken();
        }
      }

      arguments.add(new Tuple<>(
        invokeLowestPrecedenceParser(tokenizer),
        identifier == null ? null : new IdentifierExpression(identifier.getValue(), identifier, identifier, tokenizer.getRawText())
      ));
    }

    // Function invocations have to be terminated with a closing parenthesis
    tk = tokenizer.consumeToken();
    if (tk == null || tk.getType() != TokenType.PARENTHESIS_CLOSE)
      throw new UnexpectedTokenError(tokenizer, tk, TokenType.PARENTHESIS_CLOSE);

    IdentifierExpression identifierExpression = new IdentifierExpression(
      tokenIdentifier.getValue(), tokenIdentifier, tokenIdentifier, tokenizer.getRawText()
    );

    return new FunctionInvocationExpression(
      identifierExpression, arguments, paren.getType() == TokenType.OPTIONAL_PARENTHESIS_OPEN,
      tokenIdentifier, tk, tokenizer.getRawText()
    );
  }

  /////////////////////// Unary Expressions ///////////////////////

  private AExpression parseNegationExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseUnaryExpression(
      (input, h, t, op) -> new InvertExpression(input, h, t, tokenizer.getRawText()),
      tokenizer, precedenceSelf, false,
      new TokenType[] { TokenType.BOOL_NOT }, null
    );
  }

  private AExpression parseParenthesisExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseUnaryExpression(
      (input, h, t, op) -> input,
      tokenizer, precedenceSelf, true,
      new TokenType[] { TokenType.PARENTHESIS_OPEN }, new TokenType[] { TokenType.PARENTHESIS_CLOSE }
    );
  }

  private AExpression parseFlipSignExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseUnaryExpression(
      (input, h, t, op) -> new FlipSignExpression(input, h, t, tokenizer.getRawText()),
      tokenizer, precedenceSelf, false,
      new TokenType[] { TokenType.MINUS }, null
    );
  }

  /////////////////////// Binary Expressions ///////////////////////

  private AExpression parseMemberAccessExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new MemberAccessExpression(lhs, rhs, op.getType() == TokenType.OPTIONAL_DOT, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.DOT, TokenType.OPTIONAL_DOT }, null
    );
  }

  private AExpression parseIndexExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new IndexExpression(lhs, rhs, op.getType() == TokenType.OPTIONAL_BRACKET_OPEN, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.RESET, precedenceSelf,
      new TokenType[] { TokenType.BRACKET_OPEN, TokenType.OPTIONAL_BRACKET_OPEN },
      new TokenType[] { TokenType.BRACKET_CLOSE, TokenType.BRACKET_CLOSE }
    );
  }

  private AExpression parseComparisonExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> {

        ComparisonOperation operator;
        switch (op.getType()) {
          case GREATER_THAN:
            operator = ComparisonOperation.GREATER_THAN;
            break;

          case GREATER_THAN_OR_EQUAL:
            operator = ComparisonOperation.GREATER_THAN_OR_EQUAL;
            break;

          case LESS_THAN:
            operator = ComparisonOperation.LESS_THAN;
            break;

          case LESS_THAN_OR_EQUAL:
            operator = ComparisonOperation.LESS_THAN_OR_EQUAL;
            break;

          default:
            throw new IllegalStateException();
        }

        return new ComparisonExpression(lhs, rhs, operator, h, t, tokenizer.getRawText());
      },
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.GREATER_THAN, TokenType.GREATER_THAN_OR_EQUAL, TokenType.LESS_THAN, TokenType.LESS_THAN_OR_EQUAL }, null
    );
  }

  private AExpression parseAssignmentExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse an assignment expression");

    Token tk = tokenizer.peekToken();

    // There's no identifier as the next token
    if (tk == null || tk.getType() != TokenType.IDENTIFIER) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not an assignment expression");

      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Save before consuming the identifier
    tokenizer.saveState(true);

    Token identifier = tk;
    tokenizer.consumeToken();

    tk = tokenizer.peekToken();

    // The identifier needs to be followed by an assign token
    if (tk == null || tk.getType() != TokenType.ASSIGN) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Not an assignment expression");

      // Put the identifier back
      tokenizer.restoreState(true);
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // No need to restore anymore, is definitely an assignment expression
    tokenizer.discardState(true);

    // Consume the assign token
    tokenizer.consumeToken();

    // Parse the expression to be assigned to this identifier
    AExpression value = invokeLowestPrecedenceParser(tokenizer);

    return new AssignmentExpression(
      new IdentifierExpression(identifier.getValue(), identifier, identifier, tokenizer.getRawText()),
      value, identifier, value.getTail(), tokenizer.getRawText()
    );
  }

  private AExpression parseNullCoalesceExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new NullCoalesceExpression(lhs, rhs, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.NULL_COALESCE }, null
    );
  }

  private AExpression parseConcatenationExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new ConcatenationExpression(lhs, rhs, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.CONCATENATE }, null
    );
  }

  private AExpression parseDisjunctionExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new DisjunctionExpression(lhs, rhs, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.BOOL_OR }, null
    );
  }

  private AExpression parseConjunctionExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new ConjunctionExpression(lhs, rhs, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.BOOL_AND }, null
    );
  }

  private AExpression parseEqualityExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> {

        EqualityOperation operator;
        switch (op.getType()) {
          case VALUE_EQUALS:
            operator = EqualityOperation.EQUAL;
            break;

          case VALUE_NOT_EQUALS:
            operator = EqualityOperation.NOT_EQUAL;
            break;

          case VALUE_EQUALS_EXACT:
            operator = EqualityOperation.EQUAL_EXACT;
            break;

          case VALUE_NOT_EQUALS_EXACT:
            operator = EqualityOperation.NOT_EQUAL_EXACT;
            break;

          default:
            throw new IllegalStateException();
        }

        return new EqualityExpression(lhs, rhs, operator, h, t, tokenizer.getRawText());
      },
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.VALUE_EQUALS, TokenType.VALUE_NOT_EQUALS, TokenType.VALUE_EQUALS_EXACT, TokenType.VALUE_NOT_EQUALS_EXACT }, null
    );
  }

  private AExpression parseAdditiveExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> {
        MathOperation operator = MathOperation.ADDITION;

        if (op.getType() == TokenType.MINUS)
          operator = MathOperation.SUBTRACTION;

        return new MathExpression(lhs, rhs, operator, h, t, tokenizer.getRawText());
      },
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.PLUS, TokenType.MINUS }, null
    );
  }

  private AExpression parseMultiplicativeExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> {
        MathOperation operator = MathOperation.MULTIPLICATION;

        if (op.getType() == TokenType.DIVISION)
          operator = MathOperation.DIVISION;

        else if (op.getType() == TokenType.MODULO)
          operator = MathOperation.MODULO;

        return new MathExpression(lhs, rhs, operator, h, t, tokenizer.getRawText());
      },
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.MULTIPLICATION, TokenType.DIVISION, TokenType.MODULO }, null
    );
  }

  private AExpression parseExponentiationExpression(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return parseBinaryExpression(
      (lhs, rhs, h, t, op) -> new MathExpression(lhs, rhs, MathOperation.POWER, h, t, tokenizer.getRawText()),
      tokenizer, PrecedenceMode.HIGHER, precedenceSelf,
      new TokenType[] { TokenType.EXPONENT }, null
    );
  }

  //////////////////////// Primary Expression ////////////////////////

  private AExpression parsePrimaryExpression(ITokenizer tokenizer) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a primary expression");

    Token tk = tokenizer.consumeToken();

    if (tk == null)
      throw new UnexpectedTokenError(tokenizer, null, TokenType.valueTypes);

    switch (tk.getType()) {
      case LONG:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found an integer");
        return new LongExpression(parseIntegerWithPossibleExponent(tk), tk, tk, tokenizer.getRawText());

      case DOUBLE:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found a double");
        return new DoubleExpression(Double.parseDouble(tk.getValue()), tk, tk, tokenizer.getRawText());

      case STRING:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found a string");
        return new StringExpression(tk.getValue(), tk, tk, tokenizer.getRawText());

      case IDENTIFIER: {
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found an identifier");
        return new IdentifierExpression(tk.getValue(), tk, tk, tokenizer.getRawText());
      }

      case TRUE:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found the true literal");
        return new LiteralExpression(LiteralType.TRUE, tk, tk, tokenizer.getRawText());

      case FALSE:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found the false literal");
      return new LiteralExpression(LiteralType.FALSE, tk, tk, tokenizer.getRawText());

      case NULL:
        logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Found the null literal");
      return new LiteralExpression(LiteralType.NULL, tk, tk, tokenizer.getRawText());

      default:
        throw new UnexpectedTokenError(tokenizer, tk, TokenType.valueTypes);
    }
  }

  //=========================================================================//
  //                                Utilities                                //
  //=========================================================================//

  /**
   * Invokes the lowest expression parser within the sequence dictated by the precedence ladder
   * @param tokenizer Current parsing context's tokenizer reference
   * @return Result of invoking the lowest expression parser
   */
  private AExpression invokeLowestPrecedenceParser(ITokenizer tokenizer) throws AEvaluatorError {
    return precedenceLadder[0].apply(tokenizer, 0);
  }

  /**
   * Invokes the next expression parser within the sequence dictated by the precedence ladder
   * @param tokenizer Current parsing context's tokenizer reference
   * @param precedenceSelf Precedence of the current expression parser wanting to invoke the next
   * @return Result of invoking the next expression parser
   */
  private AExpression invokeNextPrecedenceParser(ITokenizer tokenizer, int precedenceSelf) throws AEvaluatorError {
    return precedenceLadder[precedenceSelf + 1].apply(tokenizer, precedenceSelf + 1);
  }

  /**
   * Searches the index within the token type array of an
   * element which has a type matching the input token
   * @param types Token types
   * @param tk Token to match the type of
   * @return Index if available, -1 otherwise
   */
  private int matchingTypeIndex(TokenType[] types, Token tk) {
    for (int i = 0; i < types.length; i++) {
      if (tk.getType() == types[i])
        return i;
    }
    return -1;
  }

  /**
   * Parses an expression with the unary expression pattern by first matching an operator, then parsing an
   * expression (with reset precedence levels) and checking for an optional following terminator
   * @param wrapper Expression wrapper which wraps the input expression and the operator into a matching expression type
   * @param tokenizer Current parsing context's tokenizer reference
   * @param precedenceSelf Precedence of the current expression parser wanting to invoke the next
   * @param resetPrecedence Whether or not to reset the precedence for the parsed input expression
   * @param operators Operators which represent this type of expression (match one)
   * @param terminators Optional terminator for each operator
   * @return Parsed expression after invoking the wrapper on it or the result of the next precedence parser if the operators didn't match
   */
  private AExpression parseUnaryExpression(
    FUnaryExpressionWrapper wrapper, ITokenizer tokenizer,
    int precedenceSelf, boolean resetPrecedence,
    TokenType[] operators, @Nullable TokenType[] terminators
  ) {
    String requiredOperatorsString = Arrays.stream(operators).map(Enum::name).collect(Collectors.joining("|"));
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a unary expression for the operator " + requiredOperatorsString);

    Token tk = tokenizer.peekToken();
    int opInd;

    // There's no not operator as the next token, hand over to the next higher precedence parser
    if (tk == null || (opInd = matchingTypeIndex(operators, tk)) < 0) {
      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Doesn't match any required operators of " + requiredOperatorsString);
      return invokeNextPrecedenceParser(tokenizer, precedenceSelf);
    }

    // Consume the operator
    Token operator = tokenizer.consumeToken();

    // Parse the following expression
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse an input for this expression");

    AExpression input;

    if (resetPrecedence)
      input = invokeLowestPrecedenceParser(tokenizer);
    else
      input = invokeNextPrecedenceParser(tokenizer, precedenceSelf);

    // Terminator requested, expect and eat it, fail otherwise
    if (terminators != null) {
      if ((tk = tokenizer.consumeToken()) == null || tk.getType() != terminators[opInd])
        throw new UnexpectedTokenError(tokenizer, tk, terminators[opInd]);
    }

    return wrapper.apply(input, operator, input.getTail(), operator);
  }

  /**
   * Parses an expression with the binary expression pattern by first invoking the next precedence parser to
   * parse the left hand side, then matching an operator as well as a right hand side with the specified
   * right hand side precedence parser. This action of parsing an operator and a right hand side will happen
   * as often as there are matching operators available. The results will be chained together to the right. After
   * each right hand side, the optional terminator will be expected, if provided.
   * @param wrapper Expression wrapper which wraps the input expression and the operator into a matching expression type
   * @param tokenizer Current parsing context's tokenizer reference
   * @param rhsPrecedence Precedence mode to use when parsing right hand side expressions
   * @param precedenceSelf Precedence of the current expression parser wanting to invoke the next
   * @param operators Operators which represent this type of expression (match one)
   * @param terminators Optional terminator for each operator
   * @return Parsed expression after invoking the wrapper on it or the result of the next precedence parser if the operators didn't match
   */
  private AExpression parseBinaryExpression(
    FBinaryExpressionWrapper wrapper, ITokenizer tokenizer,
    PrecedenceMode rhsPrecedence, int precedenceSelf,
    TokenType[] operators, @Nullable TokenType[] terminators
  ) throws AEvaluatorError {
    logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a binary expression for the operator " + Arrays.stream(operators).map(Enum::name).collect(Collectors.joining("|")));

    AExpression lhs = invokeNextPrecedenceParser(tokenizer, precedenceSelf);

    Token tk, head = lhs.getHead();
    int opInd;

    while (
      (tk = tokenizer.peekToken()) != null &&
      (opInd = matchingTypeIndex(operators, tk)) >= 0
    ) {
      // Consume the operator
      tokenizer.consumeToken();

      logger.log(Level.FINEST, () -> DebugLogSource.PARSER + "Trying to parse a rhs for this operation");

      AExpression rhs;

      if (rhsPrecedence == PrecedenceMode.HIGHER)
        rhs = invokeNextPrecedenceParser(tokenizer, precedenceSelf);
      else if (rhsPrecedence == PrecedenceMode.RESET)
        rhs = invokeLowestPrecedenceParser(tokenizer);
      else
        throw new IllegalStateException("Unimplemented precedence mode");

      Token operator = tk;

      // Terminator requested, expect and eat it, fail otherwise
      if (terminators != null) {
        if ((tk = tokenizer.consumeToken()) == null || tk.getType() != terminators[opInd])
          throw new UnexpectedTokenError(tokenizer, tk, terminators[opInd]);
      }

      lhs = wrapper.apply(lhs, rhs, head, rhs.getTail(), operator);
    }

    return lhs;
  }

  /**
   * Parses an integer token which supports exponent notation, which java does not (only on doubles).
   * @param tk Token of integer type
   * @return Value represented by the token
   */
  private int parseIntegerWithPossibleExponent(Token tk) {
    String tokenValue = tk.getValue();
    int exponentIndicatorIndex = tokenValue.indexOf('e');

    int number;

    if (exponentIndicatorIndex < 0)
      number = Integer.parseInt(tokenValue);
    else {
      int numberValue = Integer.parseInt(tokenValue.substring(0, exponentIndicatorIndex));
      int exponentValue = Integer.parseInt(tokenValue.substring(exponentIndicatorIndex + 1));
      number = numberValue * (int) Math.pow(10, exponentValue);
    }

    return number;
  }
}
