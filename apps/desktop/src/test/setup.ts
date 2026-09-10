import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
afterEach(cleanup);
window.scrollTo = () => {};
Object.defineProperty(window,'matchMedia',{writable:true,value:()=>({matches:false,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){},dispatchEvent(){return false;}})});
