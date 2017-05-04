package smalltalk.compiler;

import jdk.nashorn.internal.ir.Block;
import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;
import smalltalk.compiler.symbols.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

	public CodeGenerator(Compiler compiler) {
		this.compiler = compiler;
	}

	/** This and defaultResult() critical to getting code to bubble up the
	 *  visitor call stack when we don't implement every method.
	 */
	@Override
	protected Code aggregateResult(Code aggregate, Code nextResult) {
		if ( aggregate!=Code.None ) {
			if ( nextResult!=Code.None ) {
				return aggregate.join(nextResult);
			}
			return aggregate;
		}
		else {
			return nextResult;
		}
	}

	@Override
	protected Code defaultResult() {
		return Code.None;
	}

	@Override
	public Code visitFile(SmalltalkParser.FileContext ctx) {
		currentScope = compiler.symtab.GLOBALS;
		visitChildren(ctx);
		return Code.None;
	}

	@Override
	public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
		currentClassScope = ctx.scope;
		pushScope(ctx.scope);
		Code code = visitChildren(ctx);
		popScope();
		currentClassScope = null;
		return code;
	}

    @Override
	public Code visitMain(SmalltalkParser.MainContext ctx) {
		currentScope = ctx.scope;
		currentClassScope = ctx.classScope;
		pushScope(ctx.scope);
		Code code = visitChildren(ctx);
		STCompiledBlock stCompiledBlock = new STCompiledBlock(ctx.classScope, (STBlock) currentScope);
		ctx.scope.compiledBlock = stCompiledBlock;
        //Somehow we need to link the block and compileblocks with stcompliedblock.blocks?? -Find all desecdents
//        List<STCompiledBlock> stCompiledBlockList = new ArrayList<>();
//        for(int i=0; i<stCompiledBlock.blocks.length; i++){
//            stCompiledBlockList.add(new STCompiledBlock(ctx.classScope, (STBlock)currentScope));
//        }
//        System.out.println("BLOCKS-"+ctx.scope.compiledBlock.blocks);
//        ctx.scope.compiledBlock.blocks[0] = stCompiledBlock;
//        List<ParseTree> blocks = Trees.(ctx);
//        System.out.println("Blocks="+blocks.size());
        int i = 0;
        List<Scope> blocks = ctx.scope.getNestedScopedSymbols();
        stCompiledBlock.blocks = new STCompiledBlock[blocks.size()];
        for(Scope b : blocks){
            STCompiledBlock stCompiledBlock1 = new STCompiledBlock(ctx.classScope, (STBlock) b);
            stCompiledBlock.blocks[i] = stCompiledBlock1;
            stCompiledBlock.blocks[i].bytecode = ((STBlock) b).compiledBlock.bytecode;
            i++;
        }
//        for(int i=0; i<blocks.size(); i++){
//            stCompiledBlock.blocks[i] =  new STCompiledBlock(ctx.classScope, (STBlock) blocks.get(i));
//            stCompiledBlock.blocks[i].bytecode = code.bytes();
//        }
        System.out.println("List of scopes= "+blocks.size());
        code = aggregateResult(code, Code.of(Bytecode.POP));
        code = aggregateResult(code, Code.of(Bytecode.SELF));
        code = aggregateResult(code, Code.of(Bytecode.RETURN));
        ctx.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return code;
	}

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code code = visit(ctx.recv);
        for(SmalltalkParser.BinaryExpressionContext e :	 ctx.args){
            code = aggregateResult(code,visit(e));
        }
        code = aggregateResult(code, Compiler.send(ctx.args.size(),getLiteralIndex(ctx.KEYWORD(0).getText())));
        return code;
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = visit(ctx.unaryExpression(0));
        return code;
    }

    @Override
    public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
        Code code = visit(ctx.primary());
        return code;
    }

    /**
     All expressions have values. Must pop each expression value off, except
     last one, which is the block return value. Visit method for blocks will
     issue block_return instruction. Visit method for method will issue
     pop self return.  If last expression is ^expr, the block_return or
     pop self return is dead code but it is always there as a failsafe.

     localVars? expr ('.' expr)* '.'?
     */
    @Override
    public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
        Code code = defaultResult();
        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            Code c = visit(statContext);
            code = aggregateResult(code, c);
        }
//        if(ctx.localVars()!=null){
//            Code c = visit(ctx.localVars());
//            code = aggregateResult(code, c);
//        }
        return code;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = visit(ctx.messageExpression());
        aggregateResult(code, visit(ctx.lvalue()));
        return code;
    }

    @Override
    public Code visitLvalue(SmalltalkParser.LvalueContext ctx) {
        VariableSymbol variableSymbol = ctx.sym;
        Code code = compiler.store_local((short) 0, variableSymbol.getInsertionOrderNumber());
        return code;
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        Code code;
        if(ctx.sym == null) {
            code = Compiler.push_global(getLiteralIndex(ctx.ID().getText()));
        } else {
            Symbol s = ctx.sym;
			// Need to tackle the symbol using push_local
            code = compiler.push_local((short) 0,s.getInsertionOrderNumber());
        }
        return code;
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        Code code = Compiler.push_nil();
        return code;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        Code code = defaultResult();
        currentScope = ctx.scope;
        STBlock stBlock = (STBlock) currentScope;
		code = aggregateResult(code, Compiler.block(stBlock.index));
		STCompiledBlock stCompiledBlock = new STCompiledBlock(currentClassScope, stBlock);
		ctx.scope.compiledBlock = stCompiledBlock;
        Code codeblock = visit(ctx.body());
        aggregateResult(codeblock, Compiler.block_return());
        ctx.scope.compiledBlock.bytecode = codeblock.bytes();
        popScope();
        return code;
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        Code code = defaultResult();
        String stringc = ctx.getText();
        if(stringc.contains("\'")) {
            stringc = stringc.replace("\'","");
        }
        if(ctx.STRING()!= null) {
             code = Compiler.push_literal(getLiteralIndex(stringc));
        } else if(ctx.getText().equals("true")){
            code = Compiler.push_true();
        } else if(ctx.getText().equals("self")){
            code = Compiler.push_self();
        } else if(ctx.getText().equals("false")){
            code = Compiler.push_false();
        } else if (ctx.getText().equals("nil")){
            code = Compiler.push_nil();
        }

        if(ctx.NUMBER() != null ){
            code = Compiler.push_int(Integer.parseInt(ctx.NUMBER().getText()));
        }
        return code;
    }

    @Override
    public Code visitSendMessage(SmalltalkParser.SendMessageContext ctx) {
        return visit(ctx.messageExpression());
    }

    public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
		return compiledMethod;
	}

	@Override
 	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
//		if ( compiler.genDbg ) {
//			e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
//		}
		Code code = e.join(Compiler.method_return());
		return code;
	}

	public void pushScope(Scope scope) {
		currentScope = scope;
	}

	public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
		currentScope = currentScope.getEnclosingScope();
	}

	public int getLiteralIndex(String s) {
        return currentClassScope.stringTable.add(s);
	}

	public Code dbgAtEndMain(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		return dbg(t.getLine(), charPos);
	}

	public Code dbgAtEndBlock(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		charPos -= 1; // point at ']'
		return dbg(t.getLine(), charPos);
	}

	public Code dbg(Token t) {
		return dbg(t.getLine(), t.getCharPositionInLine());
	}

	public Code dbg(int line, int charPos) {
		return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
	}

	public Code store(String id) {
		return null;
	}

	public Code push(String id) {
		return null;
	}

	public Code sendKeywordMsg(ParserRuleContext receiver,
							   Code receiverCode,
							   List<SmalltalkParser.BinaryExpressionContext> args,
							   List<TerminalNode> keywords)
	{
        return null;
	}

	public String getProgramSourceForSubtree(ParserRuleContext ctx) {
		return null;
	}
}
