package com.mivik.kamet

import com.mivik.kot.escape
import org.kiot.lexer.Lexer
import org.kiot.lexer.LexerAction
import org.kiot.lexer.LexerData
import org.kiot.lexer.LexerState

internal sealed class Token {
	override fun toString(): String = javaClass.simpleName

	object EOF : Token()

	object Val : Token()
	object Var : Token()
	object LeftParenthesis : Token()
	object RightParenthesis : Token()
	object LeftBracket : Token()
	object RightBracket : Token()
	object LeftBrace : Token()
	object RightBrace : Token()
	object Colon : Token()
	object Comma : Token()
	object Function : Token()
	object Return : Token()
	object If : Token()
	object Else : Token()
	object While : Token()
	object Do : Token()
	object Const : Token()
	object Newline : Token()
	object NumberSign : Token()
	object Struct : Token()
	object Null : Token()
	object SizeOf : Token()

	class Identifier(val name: String) : Token() {
		override fun toString(): String = "Identifier($name)"
	}

	class StringLiteral(val value: String) : Token() {
		override fun toString(): String = "StringLiteral(${value.escape()})"
	}

	class Constant(val literal: String, val type: Type.Primitive) : Token() {
		override fun toString(): String = "Constant($literal, $type)"
	}
}

internal sealed class UnaryOp(val symbol: String) : Token() {
	object Negative : UnaryOp("-")
	object Inverse : UnaryOp("~")
	object Not : UnaryOp("!")
	object Increment : UnaryOp("++")
	object Decrement : UnaryOp("--")
	object Indirection : UnaryOp("*")
	object AddressOf : UnaryOp("&")
}

internal open class BinOp(val symbol: String, val precedence: Int, val returnBoolean: Boolean = false) : Token() {
	object Plus : BinOp("+", 9)
	object Minus : BinOp("-", 9)
	object Multiply : BinOp("*", 10)
	object Divide : BinOp("/", 10)
	object Reminder : BinOp("%", 10)
	object Equal : BinOp("==", 6, true)
	object NotEqual : BinOp("!=", 6, true)
	object Less : BinOp("<", 7, true)
	object LessOrEqual : BinOp("<=", 7, true)
	object Greater : BinOp(">", 7, true)
	object GreaterOrEqual : BinOp(">=", 7, true)
	object ShiftLeft : BinOp("<<", 8)
	object ShiftRight : BinOp(">>", 8)
	object And : BinOp("&&", 2, true)
	object Or : BinOp("||", 1, true)
	object BitwiseAnd : BinOp("&", 5)
	object BitwiseOr : BinOp("|", 3)
	object Xor : BinOp("^", 4)
	object Assign : BinOp("=", 0)
	object AccessMember : BinOp(".", 11)
	object As : BinOp("as", 11)

	open class AssignOperators(val originalOp: BinOp) : BinOp(originalOp.symbol + "=", 0)
	object PlusAssign : AssignOperators(Plus)
	object MinusAssign : AssignOperators(Minus)
	object MultiplyAssign : AssignOperators(Multiply)
	object DivideAssign : AssignOperators(Divide)
	object ReminderAssign : AssignOperators(Reminder)
	object BitwiseAndAssign : AssignOperators(BitwiseAnd)
	object BitwiseOrAssign : AssignOperators(BitwiseOr)
	object XorAssign : AssignOperators(Xor)
	object ShiftLeftAssign : AssignOperators(ShiftLeft)
	object ShiftRightAssign : AssignOperators(ShiftRight)
}

private enum class State : LexerState {
	IN_STRING
}

private enum class Action : LexerAction {
	VAL, VAR, ENTER_STRING, ESCAPE_CHAR, UNICODE_CHAR, EXIT_STRING, PLAIN_TEXT, CONST, NEWLINE, STRUCT, NULL, AS, SIZEOF, CHAR_LITERAL,
	IDENTIFIER, INT_LITERAL, LONG_LITERAL, SINGLE_CHAR_OPERATOR, DOUBLE_CHAR_OPERATOR, DOUBLE_LITERAL, BOOLEAN_LITERAL,
	UNSIGNED_INT_LITERAL, UNSIGNED_LONG_LITERAL, FUNCTION, RETURN, IF, ELSE, WHILE, DO, SHIFT_LEFT_ASSIGN, SHIFT_RIGHT_ASSIGN
}

internal class Lexer(chars: CharSequence) : Lexer<Token>(data, chars) {
	companion object {
		val data = LexerData.build {
			options.strict = false
			options.minimize = true
			state(default) {
				"[ \t]+".ignore()
				"//[^\r\n]*".ignore()
				"/\\*([^*]|(\\*+[^*/]))*\\*+/".ignore()
				"\r|\n|\r\n" action Action.NEWLINE
				"<<=" action Action.SHIFT_LEFT_ASSIGN
				">>=" action Action.SHIFT_RIGHT_ASSIGN
				"[+\\-*/&\\|\\^%]=|&&|==|!=|<<|>>|<=|>=|\\|\\||\\+\\+|--" action Action.DOUBLE_CHAR_OPERATOR
				"[+\\-*/&\\|\\^<>%\\(\\)\\{\\}:,=~!#\\[\\]\\.]" action Action.SINGLE_CHAR_OPERATOR
				"'(\\\\u[0-9a-fA-F]{4}|\\\\.|.)'" action Action.CHAR_LITERAL
				"val" action Action.VAL
				"var" action Action.VAR
				"fun" action Action.FUNCTION
				"return" action Action.RETURN
				"while" action Action.WHILE
				"const" action Action.CONST
				"struct" action Action.STRUCT
				"sizeof" action Action.SIZEOF
				"null" action Action.NULL
				"do" action Action.DO
				"as" action Action.AS
				"if" action Action.IF
				"else" action Action.ELSE
				"true|false" action Action.BOOLEAN_LITERAL
				"[\\w\$_][\\w\\d\$_]*" action Action.IDENTIFIER
				"\\d+UL" action Action.UNSIGNED_LONG_LITERAL
				"\\d+U" action Action.UNSIGNED_INT_LITERAL
				"\\d+L" action Action.LONG_LITERAL
				"\\d+" action Action.INT_LITERAL
				"\"" action Action.ENTER_STRING
				"((0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+\\-]?[0-9]*)?)|Infinity|-Infinity|NaN" action Action.DOUBLE_LITERAL
			}
			state(State.IN_STRING) {
				"\\\\u[0-9a-fA-F]{4}" action Action.UNICODE_CHAR
				"\\\\." action Action.ESCAPE_CHAR
				"\"" action Action.EXIT_STRING
				"." action Action.PLAIN_TEXT
			}
		}
	}

	private val stringContent = StringBuilder()

	override fun lex(): Token = super.lex() ?: Token.EOF

	override fun onAction(action: Int) {
		when (Action.values()[action - 1]) {
			Action.VAL -> returnValue(Token.Val)
			Action.VAR -> returnValue(Token.Var)
			Action.NEWLINE -> returnValue(Token.Newline)
			Action.FUNCTION -> returnValue(Token.Function)
			Action.RETURN -> returnValue(Token.Return)
			Action.IF -> returnValue(Token.If)
			Action.ELSE -> returnValue(Token.Else)
			Action.WHILE -> returnValue(Token.While)
			Action.DO -> returnValue(Token.Do)
			Action.CONST -> returnValue(Token.Const)
			Action.STRUCT -> returnValue(Token.Struct)
			Action.AS -> returnValue(BinOp.As)
			Action.NULL -> returnValue(Token.Null)
			Action.SIZEOF -> returnValue(Token.SizeOf)
			Action.IDENTIFIER -> returnValue(Token.Identifier(string()))
			Action.CHAR_LITERAL -> returnValue(Token.Constant(string(), Type.Primitive.Integer.Char))
			Action.DOUBLE_LITERAL -> returnValue(Token.Constant(string(), Type.Primitive.Real.Double))
			Action.UNSIGNED_INT_LITERAL ->
				returnValue(Token.Constant(chars.substring(lastMatch, index - 1), Type.Primitive.Integer.UInt))
			Action.UNSIGNED_LONG_LITERAL ->
				returnValue(Token.Constant(chars.substring(lastMatch, index - 2), Type.Primitive.Integer.ULong))
			Action.INT_LITERAL -> returnValue(Token.Constant(string(), Type.Primitive.Integer.Int))
			Action.LONG_LITERAL ->
				returnValue(Token.Constant(chars.substring(lastMatch, index - 1), Type.Primitive.Integer.Long))
			Action.BOOLEAN_LITERAL -> returnValue(Token.Constant(string(), Type.Primitive.Boolean))
			Action.SHIFT_LEFT_ASSIGN -> returnValue(BinOp.ShiftLeftAssign)
			Action.SHIFT_RIGHT_ASSIGN -> returnValue(BinOp.ShiftRightAssign)
			Action.DOUBLE_CHAR_OPERATOR -> returnValue(
				when (string()) {
					"==" -> BinOp.Equal
					"!=" -> BinOp.NotEqual
					"<=" -> BinOp.LessOrEqual
					">=" -> BinOp.GreaterOrEqual
					"<<" -> BinOp.ShiftLeft
					">>" -> BinOp.ShiftRight
					"&&" -> BinOp.And
					"||" -> BinOp.Or
					"++" -> UnaryOp.Increment
					"--" -> UnaryOp.Decrement
					"+=" -> BinOp.PlusAssign
					"-=" -> BinOp.MinusAssign
					"*=" -> BinOp.MultiplyAssign
					"/=" -> BinOp.DivideAssign
					"%=" -> BinOp.ReminderAssign
					"&=" -> BinOp.BitwiseAndAssign
					"|=" -> BinOp.BitwiseOrAssign
					"^=" -> BinOp.XorAssign
					else -> unreachable()
				}
			)
			Action.SINGLE_CHAR_OPERATOR -> returnValue(
				when (chars[lastMatch]) {
					'+' -> BinOp.Plus
					'-' -> BinOp.Minus
					'*' -> BinOp.Multiply
					'/' -> BinOp.Divide
					'&' -> BinOp.BitwiseAnd
					'|' -> BinOp.BitwiseOr
					'^' -> BinOp.Xor
					'>' -> BinOp.Greater
					'<' -> BinOp.Less
					'%' -> BinOp.Reminder
					'=' -> BinOp.Assign
					'(' -> Token.LeftParenthesis
					')' -> Token.RightParenthesis
					'[' -> Token.LeftBracket
					']' -> Token.RightBracket
					'{' -> Token.LeftBrace
					'}' -> Token.RightBrace
					':' -> Token.Colon
					',' -> Token.Comma
					'~' -> UnaryOp.Inverse
					'!' -> UnaryOp.Not
					'#' -> Token.NumberSign
					'.' -> BinOp.AccessMember
					else -> unreachable()
				}
			)
			Action.ENTER_STRING -> switchState(State.IN_STRING)
			Action.UNICODE_CHAR -> stringContent.append(chars.substring(lastMatch + 2, index).toShort(16).toChar())
			Action.ESCAPE_CHAR -> stringContent.append(chars[lastMatch + 1].escape())
			Action.PLAIN_TEXT -> stringContent.append(string())
			Action.EXIT_STRING -> {
				returnValue(Token.StringLiteral(stringContent.toString())) // not returning immediately
				stringContent.clear()
				switchState(0)
			}
		}
	}
}