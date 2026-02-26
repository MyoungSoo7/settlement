import React from 'react';
import { useNavigate } from 'react-router-dom';

const StartPage: React.FC = () => {
  const navigate = useNavigate();

  const handleRoleSelection = (role: string) => {
    // 역할을 쿼리 파라미터로 넘겨주거나, 바로 로그인 페이지로 이동
    // 여기서는 로그인 페이지로 이동시키되, 퀵 로그인 정보를 함께 전달할 수도 있음
    navigate(`/login?role=${role}`);
  };

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col items-center justify-center p-4">
      <div className="max-w-4xl w-full bg-white rounded-2xl shadow-xl overflow-hidden flex flex-col md:flex-row">
        {/* Left Side - Branding */}
        <div className="md:w-1/3 bg-blue-600 p-12 text-white flex flex-col justify-center items-center text-center">
          <h1 className="text-5xl font-bold mb-4 italic">Lemuel</h1>
          <p className="text-xl opacity-80">혁신적인 정산 및 쇼핑 플랫폼</p>
          <div className="mt-12 w-24 h-1 bg-white opacity-30 rounded-full"></div>
        </div>

        {/* Right Side - Selection */}
        <div className="md:w-2/3 p-12 flex flex-col justify-center">
          <h2 className="text-3xl font-bold text-gray-800 mb-8">로그인 유형을 선택해 주세요</h2>
          
          <div className="grid grid-cols-1 gap-6">
            {/* USER Button */}
            <button
              onClick={() => handleRoleSelection('USER')}
              className="group flex items-center p-6 border-2 border-gray-200 rounded-xl hover:border-blue-500 hover:bg-blue-50 transition-all duration-300 text-left"
            >
              <div className="w-16 h-16 bg-blue-100 rounded-lg flex items-center justify-center mr-6 group-hover:bg-blue-500 transition-colors">
                <svg className="w-8 h-8 text-blue-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path>
                </svg>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-800">쇼핑하기 (USER)</h3>
                <p className="text-gray-500">상품을 둘러보고 주문 및 결제를 진행합니다.</p>
              </div>
            </button>

            {/* MANAGER Button */}
            <button
              onClick={() => handleRoleSelection('MANAGER')}
              className="group flex items-center p-6 border-2 border-gray-200 rounded-xl hover:border-green-500 hover:bg-green-50 transition-all duration-300 text-left"
            >
              <div className="w-16 h-16 bg-green-100 rounded-lg flex items-center justify-center mr-6 group-hover:bg-green-500 transition-colors">
                <svg className="w-8 h-8 text-green-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01"></path>
                </svg>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-800">매니저 모드 (MANAGER)</h3>
                <p className="text-gray-500">상품 재고 관리 및 주문 현황을 확인합니다.</p>
              </div>
            </button>

            {/* ADMIN Button */}
            <button
              onClick={() => handleRoleSelection('ADMIN')}
              className="group flex items-center p-6 border-2 border-gray-200 rounded-xl hover:border-purple-500 hover:bg-purple-50 transition-all duration-300 text-left"
            >
              <div className="w-16 h-16 bg-purple-100 rounded-lg flex items-center justify-center mr-6 group-hover:bg-purple-500 transition-colors">
                <svg className="w-8 h-8 text-purple-600 group-hover:text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"></path>
                </svg>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-800">관리자 설정 (ADMIN)</h3>
                <p className="text-gray-500">시스템 설정, 정산 승인 및 회원 관리 업무를 수행합니다.</p>
              </div>
            </button>
          </div>
        </div>
      </div>
      
      <p className="mt-8 text-gray-400 text-sm">© 2026 Lemuel Systems. All rights reserved.</p>
    </div>
  );
};

export default StartPage;
