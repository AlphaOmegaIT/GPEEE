package me.blvckbytes.gpeee.tokenizer;

import me.blvckbytes.gpeee.error.AEvaluatorError;
import me.blvckbytes.gpeee.error.UnknownTokenError;
import org.jetbrains.annotations.Nullable;

import java.util.Stack;
import java.util.function.Consumer;

public class Tokenizer implements ITokenizer {

  private final String rawText;
  private final Consumer<String> logger;
  private final char[] text;
  private final Stack<TokenizerState> saveStates;
  private TokenizerState state;

  public Tokenizer(Consumer<String> logger, String text) {
    this.rawText = text;
    this.logger = logger;
    this.text = text.toCharArray();
    this.state = new TokenizerState();
    this.saveStates = new Stack<>();
  }

  //=========================================================================//
  //                                ITokenizer                               //
  //=========================================================================//

  @Override
  public String getRawText() {
    return rawText;
  }

  @Override
  public boolean hasNextChar() {
    return state.charIndex < this.text.length;
  }

  @Override
  public boolean isConsideredWhitespace(char c) {
    return c == ' ' || c == '\t';
  }

  @Override
  public void saveState(boolean debugLog) {
    this.saveStates.push(this.state.copy());

    if (debugLog)
      logger.accept("Saved state " + this.saveStates.size() + " (charIndex=" + state.charIndex + ")");
  }

  @Override
  public void restoreState(boolean debugLog) {
    int sizeBefore = this.saveStates.size();
    this.state = this.saveStates.pop();

    if (debugLog)
      logger.accept("Restored state " + sizeBefore + " (charIndex=" + state.charIndex + ")");
  }

  @Override
  public TokenizerState discardState(boolean debugLog) {
    int sizeBefore = this.saveStates.size();
    TokenizerState state = this.saveStates.pop();

    if (debugLog)
      logger.accept("Discarded state " + sizeBefore + " (charIndex=" + state.charIndex + ")");

    return state;
  }

  @Override
  public char nextChar() {
    char next = this.text[state.charIndex++];

    if (next == '\n') {
      ++state.row;
      state.colStack.push(state.col);
      state.col = 0;
    } else {
      ++state.col;
    }

    return next;
  }

  @Override
  public @Nullable Character previousChar() {
    return state.charIndex == 0 ? null : this.text[state.charIndex - 2];
  }

  @Override
  public char peekNextChar() {
    return this.text[state.charIndex];
  }

  @Override
  public void undoNextChar() {
    char lastChar = this.text[state.charIndex - 1];

    if (lastChar == '\n') {
      --state.row;
      state.col = state.colStack.pop();
    }

    else
      --state.col;

    state.charIndex--;
  }

  @Override
  public @Nullable Token peekToken() throws AEvaluatorError {
    if (state.currentToken == null)
      readNextToken();

    logger.accept("Peeked token " + state.currentToken);
    return state.currentToken;
  }

  @Override
  public @Nullable Token consumeToken() throws AEvaluatorError {
    if (state.currentToken == null)
      readNextToken();

    Token result = state.currentToken;
    readNextToken();

    logger.accept("Consumed token " + result);
    return result;
  }

  @Override
  public int getCurrentRow() {
    return state.row;
  }

  @Override
  public int getCurrentCol() {
    return state.col;
  }

  //=========================================================================//
  //                                Utilities                                //
  //=========================================================================//

  private void eatWhitespace() {
    while (hasNextChar() && (isConsideredWhitespace(peekNextChar()) || peekNextChar() == '\n'))
      nextChar();
  }

  /**
   * Reads the next token or null if nothing is available into the local state
   */
  private void readNextToken() throws AEvaluatorError {
    eatWhitespace();

    if (!hasNextChar()) {
      state.currentToken = null;
      return;
    }

    for (TokenType tryType : TokenType.valuesInTrialOrder) {
      FTokenReader reader = tryType.getTokenReader();

      // Token not yet implemented
      if (reader == null)
        continue;

      saveState(false);

      String result = reader.apply(this);

      // This reader wasn't successful, restore and try the next in line
      if (result == null) {
        restoreState(false);
        continue;
      }

      // Discard the saved state (to move forwards) but use it as the token's row/col supplier
      TokenizerState previousState = discardState(false);
      state.currentToken = new Token(tryType, previousState.row, previousState.col, result);
      return;
    }

    // No tokenizer matched
    throw new UnknownTokenError(state.row, state.col, rawText);
  }
}
