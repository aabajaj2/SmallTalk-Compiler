# Smalltalk-Compiler
Built a full compiler for a subset of Smalltalk using ANTLR and JAVA.
Example:

SmallTalk code 
```
Transcript show: 'hello'.
```
Bytecode generated
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
