import React, { useState } from 'react';
import { ProductCreateRequest, ProductResponse, ProductImageResponse } from '@/types';
import { productApi } from '@/api/product';
import ImageUpload from './ImageUpload';
import api from '@/api/axios';

interface CreateProductFormProps {
  onSuccess?: () => void;
  onCancel?: () => void;
}

const CreateProductForm: React.FC<CreateProductFormProps> = ({ onSuccess, onCancel }) => {
  const [formData, setFormData] = useState<ProductCreateRequest>({
    name: '',
    description: '',
    price: 0,
    stockQuantity: 0,
  });

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [createdProduct, setCreatedProduct] = useState<ProductResponse | null>(null);
  const [productImages, setProductImages] = useState<ProductImageResponse[]>([]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!formData.name.trim()) {
      newErrors.name = '상품명은 필수입니다.';
    } else if (formData.name.length > 200) {
      newErrors.name = '상품명은 200자를 초과할 수 없습니다.';
    }

    if (formData.price < 0) {
      newErrors.price = '가격은 0 이상이어야 합니다.';
    }

    if (formData.stockQuantity < 0) {
      newErrors.stockQuantity = '재고 수량은 0 이상이어야 합니다.';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;

    let parsedValue: string | number = value;
    if (name === 'price' || name === 'stockQuantity') {
      parsedValue = value === '' ? 0 : Number(value);
    }

    setFormData(prev => ({
      ...prev,
      [name]: parsedValue,
    }));

    // 입력 시 해당 필드의 에러 제거
    if (errors[name]) {
      setErrors(prev => {
        const newErrors = { ...prev };
        delete newErrors[name];
        return newErrors;
      });
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) {
      return;
    }

    setIsSubmitting(true);
    setErrorMessage('');
    setSuccessMessage('');

    try {
      const product = await productApi.createProduct(formData);
      setCreatedProduct(product);
      setSuccessMessage('상품이 성공적으로 등록되었습니다! 이제 이미지를 추가할 수 있습니다.');

      // 폼 초기화
      setFormData({
        name: '',
        description: '',
        price: 0,
        stockQuantity: 0,
      });
    } catch (error: any) {
      const message = error.response?.data?.message || '상품 등록에 실패했습니다.';
      setErrorMessage(message);
      console.error('상품 등록 실패:', error);
    } finally {
      setIsSubmitting(false);
    }
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

  const handleComplete = () => {
    setCreatedProduct(null);
    setProductImages([]);
    if (onSuccess) {
      onSuccess();
    }
  };

  const handleReset = () => {
    setFormData({
      name: '',
      description: '',
      price: 0,
      stockQuantity: 0,
    });
    setErrors({});
    setErrorMessage('');
    setSuccessMessage('');
    setCreatedProduct(null);
    setProductImages([]);
  };

  // If product is created, show image upload interface
  if (createdProduct) {
    return (
      <div className="max-w-2xl mx-auto p-6 bg-white rounded-lg shadow-md">
        <h2 className="text-2xl font-bold mb-6 text-gray-900">상품 이미지 추가</h2>

        <div className="mb-4 p-4 bg-green-100 border border-green-400 text-green-800 rounded">
          상품 "{createdProduct.name}"이(가) 성공적으로 등록되었습니다!
        </div>

        <div className="mb-6">
          <p className="text-sm text-gray-700 mb-4">
            이제 상품 이미지를 추가할 수 있습니다. 이미지는 나중에도 추가하거나 수정할 수 있습니다.
          </p>

          <ImageUpload
            productId={createdProduct.id}
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
            onImagesChange={() => fetchProductImages(createdProduct.id)}
          />
        </div>

        <div className="flex gap-4 pt-4">
          <button
            onClick={handleComplete}
            className="flex-1 py-3 px-6 rounded-lg bg-blue-600 text-white font-semibold hover:bg-blue-700 transition-colors duration-200"
          >
            완료
          </button>
          {onCancel && (
            <button
              onClick={onCancel}
              className="px-6 py-3 border border-gray-300 rounded-lg text-gray-900 font-semibold hover:bg-gray-50 transition-colors duration-200"
            >
              닫기
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto p-6 bg-white rounded-lg shadow-md">
      <h2 className="text-2xl font-bold mb-6 text-gray-900">신규 상품 등록</h2>

      {successMessage && (
        <div className="mb-4 p-4 bg-green-100 border border-green-400 text-green-800 rounded">
          {successMessage}
        </div>
      )}

      {errorMessage && (
        <div className="mb-4 p-4 bg-red-100 border border-red-400 text-red-800 rounded">
          {errorMessage}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-6">
        {/* 상품명 */}
        <div>
          <label htmlFor="name" className="block text-sm font-semibold text-gray-900 mb-2">
            상품명 <span className="text-red-600">*</span>
          </label>
          <input
            type="text"
            id="name"
            name="name"
            value={formData.name}
            onChange={handleChange}
            className={`w-full px-4 py-2 border rounded-lg text-gray-900 placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
              errors.name ? 'border-red-500' : 'border-gray-300'
            }`}
            placeholder="상품명을 입력하세요"
            maxLength={200}
          />
          {errors.name && (
            <p className="mt-1 text-sm text-red-600">{errors.name}</p>
          )}
          <p className="mt-1 text-xs text-gray-600">
            {formData.name.length}/200자
          </p>
        </div>

        {/* 상품 설명 */}
        <div>
          <label htmlFor="description" className="block text-sm font-semibold text-gray-900 mb-2">
            상품 설명
          </label>
          <textarea
            id="description"
            name="description"
            value={formData.description}
            onChange={handleChange}
            rows={4}
            className="w-full px-4 py-2 border border-gray-300 rounded-lg text-gray-900 placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            placeholder="상품 설명을 입력하세요 (선택사항)"
          />
        </div>

        {/* 가격 */}
        <div>
          <label htmlFor="price" className="block text-sm font-semibold text-gray-900 mb-2">
            가격 (원) <span className="text-red-600">*</span>
          </label>
          <input
            type="number"
            id="price"
            name="price"
            value={formData.price}
            onChange={handleChange}
            min="0"
            step="0.01"
            className={`w-full px-4 py-2 border rounded-lg text-gray-900 placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
              errors.price ? 'border-red-500' : 'border-gray-300'
            }`}
            placeholder="0"
          />
          {errors.price && (
            <p className="mt-1 text-sm text-red-600">{errors.price}</p>
          )}
          <p className="mt-1 text-xs text-gray-600">
            {new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(formData.price)}
          </p>
        </div>

        {/* 재고 수량 */}
        <div>
          <label htmlFor="stockQuantity" className="block text-sm font-semibold text-gray-900 mb-2">
            초기 재고 수량 <span className="text-red-600">*</span>
          </label>
          <input
            type="number"
            id="stockQuantity"
            name="stockQuantity"
            value={formData.stockQuantity}
            onChange={handleChange}
            min="0"
            step="1"
            className={`w-full px-4 py-2 border rounded-lg text-gray-900 placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
              errors.stockQuantity ? 'border-red-500' : 'border-gray-300'
            }`}
            placeholder="0"
          />
          {errors.stockQuantity && (
            <p className="mt-1 text-sm text-red-600">{errors.stockQuantity}</p>
          )}
        </div>

        {/* 버튼 */}
        <div className="flex gap-4 pt-4">
          <button
            type="submit"
            disabled={isSubmitting}
            className={`flex-1 py-3 px-6 rounded-lg text-white font-semibold ${
              isSubmitting
                ? 'bg-gray-400 cursor-not-allowed'
                : 'bg-blue-600 hover:bg-blue-700 active:bg-blue-800'
            } transition-colors duration-200`}
          >
            {isSubmitting ? '등록 중...' : '상품 등록'}
          </button>

          <button
            type="button"
            onClick={handleReset}
            disabled={isSubmitting}
            className="px-6 py-3 border border-gray-300 rounded-lg text-gray-900 font-semibold hover:bg-gray-50 active:bg-gray-100 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            초기화
          </button>

          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              disabled={isSubmitting}
              className="px-6 py-3 border border-gray-300 rounded-lg text-gray-900 font-semibold hover:bg-gray-50 active:bg-gray-100 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              취소
            </button>
          )}
        </div>
      </form>

      {/* 안내 문구 */}
      <div className="mt-6 p-4 bg-blue-50 rounded-lg border border-blue-200">
        <h3 className="text-sm font-semibold text-blue-900 mb-2">📝 상품 등록 안내</h3>
        <ul className="text-sm text-blue-900 space-y-1">
          <li>• 상품명은 필수이며 최대 200자까지 입력 가능합니다.</li>
          <li>• 가격은 0 이상의 숫자만 입력 가능합니다.</li>
          <li>• 재고 수량은 0 이상의 정수만 입력 가능합니다.</li>
          <li>• 등록된 상품은 자동으로 '판매 중(ACTIVE)' 상태가 됩니다.</li>
        </ul>
      </div>
    </div>
  );
};

export default CreateProductForm;
