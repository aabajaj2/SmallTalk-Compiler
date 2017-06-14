# smalltalk-compiler
Compiler for Subset of SmallTalk
Built a full compiler for a subset of Smalltalk using ANTLR and JAVA. The java project generated bytecode for a given SmallTalk code with symbol table information. 
Example:
SmallTalk Code - 
```
Transcript show: 'hello'.
```
Bytecode generated- 
```
name: MainClass
superClass: 
fields: 
literals: 'Transcript','hello','show:'
methods:
    name: main
    qualifiedName: MainClass>>main
    nargs: 0
    nlocals: 0
    0000:  push_global    'Transcript'
    0003:  push_literal   'hello'
    0006:  send           1, 'show:'
    0011:  pop              
    0012:  self             
    0013:  return           
```
