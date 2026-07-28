import { createContext, useContext } from 'react';
import { ProductResponse } from '@/types';

/**
 * 장바구니 컨텍스트와 소비 훅 — 컴포넌트(CartProvider)와 <b>파일을 분리</b>한다.
 *
 * <p>이유는 {@link ./useToast} 와 같다: 컴포넌트 파일에서 컴포넌트 외의 값을 export 하면
 * Fast Refresh 가 상태를 잃는다(react-refresh/only-export-components).
 */
export interface CartItem {
  product: ProductResponse;
  quantity: number;
}

export interface CartContextType {
  items: CartItem[];
  addItem: (product: ProductResponse) => void;
  removeItem: (productId: number) => void;
  updateQuantity: (productId: number, quantity: number) => void;
  clearCart: () => void;
  totalAmount: number;
  totalCount: number;
}

export const CartContext = createContext<CartContextType | null>(null);

export const useCart = (): CartContextType => {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used within CartProvider');
  return ctx;
};
