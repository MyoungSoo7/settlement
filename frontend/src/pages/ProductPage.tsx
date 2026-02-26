import React, { useState, useEffect } from 'react';
import CreateProductForm from '@/components/product/CreateProductForm';
import ProductList from '@/components/product/ProductList';
import ImageUpload from '@/components/product/ImageUpload';
import InventoryTab from '@/components/product/InventoryTab';
import { ProductResponse, ProductImageResponse } from '@/types';
import { productApi } from '@/api/product';
import { useToast } from '@/contexts/ToastContext';
import api from '@/api/axios';

const ProductPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'list' | 'create' | 'inventory'>('list');
  const [refreshTrigger, setRefreshTrigger] = useState(0);
  const [selectedProduct, setSelectedProduct] = useState<ProductResponse | null>(null);
  const [productImages, setProductImages] = useState<ProductImageResponse[]>([]);
  const [isEditing, setIsEditing] = useState(false);
  const [editForm, setEditForm] = useState({
    name: '',
    description: '',
    price: 0,
    stockQuantity: 0,
  });
  const { showToast } = useToast();

  const handleProductCreated = () => {
    setRefreshTrigger(prev => prev + 1);
    setActiveTab('list');
  };

  const fetchProductImages = async (productId: number) => {
    try {
      const response = await api.get<ProductImageResponse[]>(`/admin/products/${productId}/images`);
      setProductImages(response.data);
    } catch (error) {
      console.error('이미지 로딩 오류:', error);
      setProductImages([]);
    }
  };

  const handleProductSelect = (product: ProductResponse) => {
    setSelectedProduct(product);
    setIsEditing(false);
    setEditForm({
      name: product.name,
      description: product.description || '',
      price: product.price,
      stockQuantity: product.stockQuantity,
    });
    fetchProductImages(product.id);
  };

  // Load images when product is selected
  useEffect(() => {
    if (selectedProduct) {
      fetchProductImages(selectedProduct.id);
    }
  }, [selectedProduct?.id]);

  const handleEditToggle = () => {
    if (isEditing && selectedProduct) {
      setEditForm({
        name: selectedProduct.name,
        description: selectedProduct.description || '',
        price: selectedProduct.price,
        stockQuantity: selectedProduct.stockQuantity,
      });
    }
    setIsEditing(!isEditing);
  };

  const handleEditSubmit = async () => {
    if (!selectedProduct) return;

    try {
      await productApi.updateProductInfo(selectedProduct.id, {
        name: editForm.name,
        description: editForm.description,
      });

      if (editForm.price !== selectedProduct.price) {
        await productApi.updateProductPrice(selectedProduct.id, {
          newPrice: editForm.price,
        });
      }

      if (editForm.stockQuantity !== selectedProduct.stockQuantity) {
        const stockDiff = editForm.stockQuantity - selectedProduct.stockQuantity;
        await productApi.updateProductStock(selectedProduct.id, {
          quantity: Math.abs(stockDiff),
          operation: stockDiff > 0 ? 'INCREASE' : 'DECREASE',
        });
      }

      showToast('상품이 수정되었습니다.', 'success');
      setIsEditing(false);
      setRefreshTrigger(prev => prev + 1);
      setSelectedProduct(null);
    } catch (error) {
      showToast('상품 수정에 실패했습니다.', 'error');
      console.error('상품 수정 오류:', error);
    }
  };

  const handleDelete = async () => {
    if (!selectedProduct) return;

    if (!window.confirm(`"${selectedProduct.name}" 상품을 정말 삭제하시겠습니까?`)) {
      return;
    }

    try {
      await productApi.discontinueProduct(selectedProduct.id);
      showToast('상품이 단종 처리되었습니다.', 'success');
      setRefreshTrigger(prev => prev + 1);
      setSelectedProduct(null);
    } catch (error) {
      showToast('상품 삭제에 실패했습니다.', 'error');
      console.error('상품 삭제 오류:', error);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* 헤더 */}
      <div className="bg-white shadow">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
          <h1 className="text-3xl font-bold text-gray-900">상품 관리</h1>
          <p className="mt-2 text-sm text-gray-600">
            상품을 등록하고 관리할 수 있습니다.
          </p>
        </div>
      </div>

      {/* 탭 네비게이션 */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 mt-6">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex space-x-8">
            <button
              onClick={() => setActiveTab('list')}
              className={`${
                activeTab === 'list'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              } whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors duration-200`}
            >
              📦 상품 목록
            </button>
            <button
              onClick={() => setActiveTab('create')}
              className={`${
                activeTab === 'create'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              } whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors duration-200`}
            >
              ➕ 상품 등록
            </button>
            <button
              onClick={() => setActiveTab('inventory')}
              className={`${
                activeTab === 'inventory'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              } whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors duration-200`}
            >
              📊 재고 관리
            </button>
          </nav>
        </div>
      </div>

      {/* 컨텐츠 */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === 'list' && (
          <div>
            <div className="mb-6 flex justify-between items-center">
              <div>
                <h2 className="text-xl font-semibold text-gray-900">상품 목록</h2>
                <p className="mt-1 text-sm text-gray-600">
                  등록된 모든 상품을 확인하고 관리할 수 있습니다.
                </p>
              </div>
              <button
                onClick={() => setActiveTab('create')}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors duration-200 font-medium"
              >
                + 새 상품 등록
              </button>
            </div>
            <ProductList
              onProductSelect={handleProductSelect}
              refreshTrigger={refreshTrigger}
            />
          </div>
        )}

        {activeTab === 'create' && (
          <div>
            <CreateProductForm
              onSuccess={handleProductCreated}
              onCancel={() => setActiveTab('list')}
            />
          </div>
        )}

        {activeTab === 'inventory' && (
          <div>
            <div className="mb-6">
              <h2 className="text-xl font-semibold text-gray-900">재고 관리</h2>
              <p className="mt-1 text-sm text-gray-600">
                상품별 재고 현황을 확인하고 입고·출고를 처리할 수 있습니다.
              </p>
            </div>
            <InventoryTab />
          </div>
        )}
      </div>

      {/* 상품 상세 모달 (선택사항) */}
      {selectedProduct && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
          onClick={() => setSelectedProduct(null)}
        >
          <div
            className="bg-white rounded-lg shadow-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="p-6">
              <div className="flex justify-between items-start mb-4">
                <h2 className="text-2xl font-bold text-gray-900">
                  {selectedProduct.name}
                </h2>
                <button
                  onClick={() => setSelectedProduct(null)}
                  className="text-gray-400 hover:text-gray-600 text-2xl"
                >
                  ×
                </button>
              </div>

              <div className="space-y-4">
                {/* 이미지 갤러리 - 항상 표시 */}
                {productImages.length > 0 && !isEditing && (
                  <div>
                    <h3 className="text-sm font-semibold text-gray-700 mb-2">상품 이미지</h3>
                    <div className="grid grid-cols-4 gap-2">
                      {productImages.map((image) => (
                        <div key={image.id} className="relative aspect-square">
                          <img
                            src={image.url}
                            alt={image.originalFileName}
                            className="w-full h-full object-cover rounded-lg border border-gray-200"
                          />
                          {image.isPrimary && (
                            <span className="absolute top-1 right-1 bg-blue-600 text-white text-xs px-2 py-1 rounded">
                              대표
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* 이미지 업로드 - 편집 모드일 때만 */}
                {isEditing && (
                  <div>
                    <h3 className="text-sm font-semibold text-gray-700 mb-2">상품 이미지 관리</h3>
                    <ImageUpload
                      productId={selectedProduct.id}
                      images={productImages.map(img => ({
                        id: img.id,
                        url: img.url,
                        originalFileName: img.originalFileName,
                        isPrimary: img.isPrimary,
                        orderIndex: img.orderIndex,
                        sizeBytes: img.sizeBytes,
                        width: img.width,
                        height: img.height,
                      }))}
                      onImagesChange={() => fetchProductImages(selectedProduct.id)}
                    />
                  </div>
                )}

                <div>
                  <h3 className="text-sm font-semibold text-gray-700 mb-1">상품 ID</h3>
                  <p className="text-gray-900">{selectedProduct.id}</p>
                </div>

                {isEditing ? (
                  <>
                    <div>
                      <label className="text-sm font-semibold text-gray-900 mb-1 block">상품명</label>
                      <input
                        type="text"
                        value={editForm.name}
                        onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-gray-900"
                      />
                    </div>

                    <div>
                      <label className="text-sm font-semibold text-gray-900 mb-1 block">설명</label>
                      <textarea
                        value={editForm.description}
                        onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                        rows={3}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-gray-900"
                      />
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="text-sm font-semibold text-gray-900 mb-1 block">가격 (원)</label>
                        <input
                          type="number"
                          value={editForm.price}
                          onChange={(e) => setEditForm({ ...editForm, price: Number(e.target.value) })}
                          className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-gray-900"
                        />
                      </div>

                      <div>
                        <label className="text-sm font-semibold text-gray-900 mb-1 block">재고</label>
                        <input
                          type="number"
                          value={editForm.stockQuantity}
                          onChange={(e) => setEditForm({ ...editForm, stockQuantity: Number(e.target.value) })}
                          className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent text-gray-900"
                        />
                      </div>
                    </div>
                  </>
                ) : (
                  <>
                    {selectedProduct.description && (
                      <div>
                        <h3 className="text-sm font-semibold text-gray-700 mb-1">설명</h3>
                        <p className="text-gray-900">{selectedProduct.description}</p>
                      </div>
                    )}

                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <h3 className="text-sm font-semibold text-gray-700 mb-1">가격</h3>
                        <p className="text-xl font-bold text-blue-600">
                          {new Intl.NumberFormat('ko-KR', {
                            style: 'currency',
                            currency: 'KRW',
                          }).format(selectedProduct.price)}
                        </p>
                      </div>

                      <div>
                        <h3 className="text-sm font-semibold text-gray-700 mb-1">재고</h3>
                        <p className="text-xl font-bold text-gray-900">
                          {selectedProduct.stockQuantity}개
                        </p>
                      </div>
                    </div>

                    <div>
                      <h3 className="text-sm font-semibold text-gray-700 mb-1">상태</h3>
                      <p className="text-gray-900">{selectedProduct.status}</p>
                    </div>

                    <div>
                      <h3 className="text-sm font-semibold text-gray-700 mb-1">
                        판매 가능 여부
                      </h3>
                      <p className="text-gray-900">
                        {selectedProduct.availableForSale ? '가능' : '불가능'}
                      </p>
                    </div>
                  </>
                )}

                <div className="pt-4 border-t">
                  <div className="text-sm text-gray-500 space-y-1">
                    <p>
                      등록일:{' '}
                      {new Date(selectedProduct.createdAt).toLocaleString('ko-KR')}
                    </p>
                    <p>
                      수정일:{' '}
                      {new Date(selectedProduct.updatedAt).toLocaleString('ko-KR')}
                    </p>
                  </div>
                </div>
              </div>

              <div className="mt-6 flex justify-end gap-3">
                {isEditing ? (
                  <>
                    <button
                      onClick={handleEditSubmit}
                      className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors duration-200 font-medium"
                    >
                      저장
                    </button>
                    <button
                      onClick={handleEditToggle}
                      className="px-6 py-2 bg-gray-600 text-white rounded-lg hover:bg-gray-700 transition-colors duration-200"
                    >
                      취소
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      onClick={handleEditToggle}
                      className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors duration-200 font-medium"
                    >
                      수정
                    </button>
                    <button
                      onClick={handleDelete}
                      className="px-6 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors duration-200 font-medium"
                    >
                      삭제
                    </button>
                    <button
                      onClick={() => setSelectedProduct(null)}
                      className="px-6 py-2 bg-gray-600 text-white rounded-lg hover:bg-gray-700 transition-colors duration-200"
                    >
                      닫기
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ProductPage;
