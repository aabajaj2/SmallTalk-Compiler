package smalltalk.compiler;

import org.antlr.symtab.ClassSymbol;
import org.antlr.symtab.Scope;
import org.antlr.symtab.Symbol;
import org.antlr.symtab.VariableSymbol;
import org.antlr.v4.runtime.Token;
import smalltalk.compiler.symbols.*;

import java.util.List;

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
        if(currentClassScope != null) {
            STCompiledBlock stCompiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
            ctx.scope.compiledBlock = stCompiledBlock;
            int i = 0;
            List<Scope> blocks = ctx.scope.getNestedScopedSymbols();
            stCompiledBlock.blocks = new STCompiledBlock[blocks.size()];
            for (Scope b : blocks) {
                stCompiledBlock.blocks[i] = ((STBlock)b).compiledBlock;
                i++;
            }
            code = aggregateResult(code, Code.of(Bytecode.POP));
            code = aggregateResult(code, Code.of(Bytecode.SELF));
            code = aggregateResult(code, Code.of(Bytecode.RETURN));
            ctx.scope.compiledBlock.bytecode = code.bytes();
            popScope();
        }
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
        int sStat = ctx.stat().size();
        int i=0;
        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            i++;
            Code c = visit(statContext);
            code = aggregateResult(code, c);
            if(i != sStat){
                code = aggregateResult(code, Code.of(Bytecode.POP));
            }
        }
        if(currentScope instanceof STMethod){
            if(!currentScope.getName().equals("main")){
                code = aggregateResult(code, Code.of(Bytecode.POP));
                code = aggregateResult(code, compiler.push_self());
                code = aggregateResult(code, compiler.method_return());
            }
        }
        return code;
    }

    @Override
    public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
        Code code = defaultResult();
        currentScope = ctx.scope;
        Code codeOfMethod = visit(ctx.methodBlock());
        pushScope(ctx.scope);
        STCompiledBlock stCompiledBlock = new STCompiledBlock(currentClassScope, (STMethod) currentScope);
        ctx.scope.compiledBlock = stCompiledBlock;
        List<Scope> blocks = ((STMethod)currentScope).getAllNestedScopedSymbols();
        addStCompiledBlock(stCompiledBlock, blocks);
        ctx.scope.compiledBlock.bytecode = codeOfMethod.bytes();
        popScope();
        return code;
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        Code code = defaultResult();
        currentScope = ctx.scope;
        pushScope(ctx.scope);
        STCompiledBlock stCompiledBlock = new STCompiledBlock(currentClassScope, (STMethod) currentScope);
        ctx.scope.compiledBlock = stCompiledBlock;
        Code codeOfMethod = visit(ctx.methodBlock());
        List<Scope> blocks = ((STMethod)currentScope).getAllNestedScopedSymbols();
        addStCompiledBlock(stCompiledBlock, blocks);
        ctx.scope.compiledBlock.bytecode = codeOfMethod.bytes();
        popScope();
        return code;
    }

    private void addStCompiledBlock(STCompiledBlock stCompiledBlock, List<Scope> blocks) {
        stCompiledBlock.blocks = new STCompiledBlock[blocks.size()];
        for(int i=0; i<blocks.size(); i++) {
            stCompiledBlock.blocks[i] = ((STBlock)blocks.get(i)).compiledBlock;
        }
    }

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        Code code = defaultResult();
        currentScope = ctx.scope;
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STMethod) currentScope);
        Code codeOfMethod = visit(ctx.methodBlock());
        code = aggregateResult(code, visit(ctx.bop()));
        ctx.scope.compiledBlock.bytecode = codeOfMethod.bytes();
        popScope();
        return code;
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code code = visit(ctx.recv);
        for(SmalltalkParser.BinaryExpressionContext e :	 ctx.args){
            code = aggregateResult(code, visit(e));
        }
        StringBuilder keywords = new StringBuilder();
        for(int i = 0; i<ctx.KEYWORD().size(); i++){
            keywords.append(ctx.KEYWORD(i).getText());
        }
        code = aggregateResult(code, compiler.send(ctx.args.size(), addToStringTable(keywords.toString())));

        return code;
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = visit(ctx.unaryExpression(0));
        if(ctx.unaryExpression().size() > 1){
            for(int i=1; i<ctx.unaryExpression().size(); i++){
                code = aggregateResult(code, visit(ctx.unaryExpression(i)));
                code = aggregateResult(code, visit(ctx.bop(i-1)));
            }
        }
        return code;
    }

    @Override
    public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
        Code code = visit(ctx.unaryExpression());
        code = aggregateResult(code, compiler.send(0, addToStringTable(ctx.ID().getText())));
        return code;
    }

    @Override
    public Code visitBop(SmalltalkParser.BopContext ctx) {
        Code code = defaultResult();
        StringBuilder opchars = new StringBuilder();
        for(int i = 0; i<ctx.opchar().size(); i++){
            opchars.append(ctx.opchar(i).getText());
        }
        String opchar = opchars.toString();
        if(opchar.equals("+") || opchar.equals("*") || opchar.equals("/") || opchar.equals("==") || opchar.equals("=") || opchar.equals("~~")) {
            code = aggregateResult(code, Compiler.send(1, addToStringTable(opchars.toString())));
        }
        return code;
    }

    @Override
    public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
        Code code = compiler.push_self();
        code = aggregateResult(code, compiler.send_super(0, addToStringTable(ctx.ID().getText())));
        return code;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = visit(ctx.messageExpression());
        code = aggregateResult(code, visit(ctx.lvalue()));
        return code;
    }

    @Override
    public Code visitLvalue(SmalltalkParser.LvalueContext ctx) {
        Code code;
        if(ctx.sym instanceof STField) {
            code = compiler.store_field(ctx.sym.getInsertionOrderNumber());
        } else {
            code = compiler.store_local((short) ((STBlock)currentScope).getRelativeScopeCount(ctx.sym.getScope().getName()),ctx.sym.getInsertionOrderNumber());
        }
        return code;
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        Code code;
        if(ctx.sym instanceof STField) {
            code = compiler.push_field(fieldindex(ctx.sym));
        } else if (ctx.sym instanceof VariableSymbol){
            code = compiler.push_local((short) ((STBlock)currentScope).getRelativeScopeCount(ctx.sym.getScope().getName()),ctx.sym.getInsertionOrderNumber());
        } else {
            code = compiler.push_global(addToStringTable(ctx.ID().getText()));
        }
        return code;
    }

    private int fieldindex(Symbol sym) {
        int index = ((STClass)sym.getScope()).getFieldIndex(sym.getName());
        ClassSymbol s = ((STClass)sym.getScope()).getSuperClassScope();
        while(s != null){
            index = index + s.getNumberOfDefinedFields();
            s = s.getSuperClassScope();
        }
        return index;
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        Code code = visitChildren(ctx);
        if(currentClassScope != null) {
            if (currentClassScope.getName().equals("MainClass")) {
                code = aggregateResult(code, compiler.push_nil());
            } else {
                code = aggregateResult(code, Code.of(Bytecode.SELF));
                code =aggregateResult(code, Code.of(Bytecode.RETURN));
            }
        }
        return code;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        Code code = defaultResult();
        currentScope = ctx.scope;
        pushScope(ctx.scope);
        STBlock stBlock = (STBlock) currentScope;
        code = aggregateResult(code, compiler.block(stBlock.index));
        Code codeOfBlock = visit(ctx.body());
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, stBlock);
        codeOfBlock = aggregateResult(codeOfBlock, compiler.block_return());
        ctx.scope.compiledBlock.bytecode = codeOfBlock.bytes();
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
        if(ctx.NUMBER() != null ){
            code = compiler.push_int(Integer.parseInt(ctx.NUMBER().getText()));
        }
        if(ctx.STRING()!= null) {
            code = compiler.push_literal(addToStringTable(stringc));
        } else if(ctx.getText().equals("true")){
            code = compiler.push_true();
        } else if(ctx.getText().equals("self")){
            code = compiler.push_self();
        } else if(ctx.getText().equals("false")){
            code = compiler.push_false();
        } else if (ctx.getText().equals("nil")){
            code = compiler.push_nil();
        }
        return code;
    }

    @Override
    public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
        Code e = visit(ctx.messageExpression());
        Code code = e.join(Compiler.method_return());
        return code;
    }

    public void pushScope(Scope scope) {
        currentScope = scope;
    }

    public void popScope() {
        currentScope = currentScope.getEnclosingScope();
    }

    public int addToStringTable(String s) {
        return currentClassScope.stringTable.add(s);
    }

    public Code dbg(Token t) {
        return dbg(t.getLine(), t.getCharPositionInLine());
    }

    public Code dbg(int line, int charPos) {
        return Compiler.dbg(addToStringTable(compiler.getFileName()), line, charPos);
    }

}
