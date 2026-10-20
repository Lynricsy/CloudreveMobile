import OpenCloudreveCore

typealias TransferListHost = ViewModelHost<TransferListState, any TransferListIntent, any TransferListEffect>
typealias UploadPickerHost = ViewModelHost<UploadPickerState, any UploadPickerIntent, any UploadPickerEffect>

extension ViewModelHost where S == UploadPickerState, I == any UploadPickerIntent {
    /// 选择文件后即时预校验（按当前目录的存储策略）
    func check(name: String, size: Int64) -> UploadRejection? {
        (viewModel as? UploadPickerViewModel)?.check(name: name, size: size)
    }
}
