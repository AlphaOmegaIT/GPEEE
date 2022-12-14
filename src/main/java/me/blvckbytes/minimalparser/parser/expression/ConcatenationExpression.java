package me.blvckbytes.minimalparser.parser.expression;

import lombok.Getter;
import me.blvckbytes.minimalparser.tokenizer.TokenType;
import org.jetbrains.annotations.Nullable;

@Getter
public class ConcatenationExpression extends BinaryExpression {

  public ConcatenationExpression(AExpression lhs, AExpression rhs) {
    super(lhs, rhs);
  }

  @Override
  protected @Nullable String getInfixSymbol() {
    return TokenType.CONCATENATE.getRepresentation();
  }
}
