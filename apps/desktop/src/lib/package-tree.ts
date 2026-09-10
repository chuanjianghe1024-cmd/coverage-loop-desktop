import type { JavaClass } from './types';
export interface PackageNode { name:string; path:string; classes:JavaClass[]; children:PackageNode[] }
/** Compact only empty single-child packages; packages containing classes retain their children. */
export function packageTree(classes:JavaClass[]):PackageNode[] {
  const roots:PackageNode[]=[],nodes=new Map<string,PackageNode>();
  for(const item of classes){
    const parts=item.packageName?item.packageName.split('.'):[''];let parent:PackageNode|undefined;
    for(let i=0;i<parts.length;i++){
      const path=parts.slice(0,i+1).join('.');let node=nodes.get(path);
      if(!node){node={name:parts[i]||'(默认包)',path,classes:[],children:[]};nodes.set(path,node);(parent?.children??roots).push(node);}
      parent=node;
    }
    parent!.classes.push(item);
  }
  const compact=(node:PackageNode):PackageNode=>{
    node.children=node.children.sort((a,b)=>a.name.localeCompare(b.name)).map(compact);
    if(!node.classes.length&&node.children.length===1){const child=node.children[0];return {...child,name:node.name+'.'+child.name};}
    return node;
  };
  return roots.sort((a,b)=>a.name.localeCompare(b.name)).map(compact);
}
export const packageMembers=(node:PackageNode):JavaClass[]=>[...node.classes,...node.children.flatMap(packageMembers)];
