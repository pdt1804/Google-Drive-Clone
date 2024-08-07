package com.example.demo.modules.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.demo.modules.logging.ActivityLoggingService;
import com.example.demo.modules.user.UserService;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;

import jakarta.annotation.PostConstruct;

@Service
public class FolderService {
	
	public static int id;

	@Autowired
	private Firestore firestore;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private DocumentService documentService;
	
	@Autowired
	private ActivityLoggingService activityLoggingService;
	
	@PostConstruct
	public void getLastFolderID() throws ExecutionException, InterruptedException
	{
		var documents = firestore.collection("File").get().get().getDocuments();
		
		if (documents.size() == 0)
		{
			id = 0;
		}
		else
		{
			for (var p : documents)
			{
				File file = p.toObject(File.class);
				if (file.getFileID() > id)
				{
					id = file.getFileID();
				}
			}
		}
	}
		
	public int GetNewFolderID() throws ExecutionException, InterruptedException {
		return ++id;
	}
	
	public String MoveToAnotherFolder(int folderID, int newFolderID, String userName) throws IOException, ExecutionException, InterruptedException {
		File existingFolder = firestore.collection("File").document(String.valueOf(folderID)).get().get().toObject(File.class);
		
		if (existingFolder.getCreatedUser().equals(userName))
		{
			documentService.UpdateSize(existingFolder.getSize(), existingFolder.getLocation(), "-");

			existingFolder.setLocation(newFolderID);
			firestore.collection("File").document(String.valueOf(existingFolder.getFileID())).set(existingFolder);
			
			documentService.UpdateSize(existingFolder.getSize(), existingFolder.getLocation(), "+");
			activityLoggingService.AddLoggingForMovingToAnotherFolder(userName, existingFolder.getFileName());

			return "Success";
		}
		
		return "This folder is not belonged to your account !";
	}
	
	public File CreateFolder(File folder, String userName) throws ExecutionException, InterruptedException {
		int folderID = GetNewFolderID();
		folder.setFileID(folderID);
		folder.setNameOnCloud(null);
		folder.setCreatedUser(userName);
		folder.setSize(0);
		folder.setCreatedTime(new Date());
		folder.setUpdatedTime(new Date());
		folder.setType(FileType.Folder);
		firestore.collection("File").document(String.valueOf(folderID)).set(folder);
		activityLoggingService.AddLoggingForCreatingFolderName(userName, folder.getFileName());
		return folder;
	}
	
	public String UpdateFolderName(File folder, String userName) throws ExecutionException, InterruptedException {
		File existingFolder = firestore.collection("File").document(String.valueOf(folder.getFileID())).get().get().toObject(File.class);
		
		if (userName.equals(existingFolder.getCreatedUser()))
		{
			String previousFileName = existingFolder.getFileName();
			existingFolder.setFileName(folder.getFileName());
			existingFolder.setUpdatedTime(new Date());
			firestore.collection("File").document(String.valueOf(existingFolder.getFileID())).set(existingFolder);
			activityLoggingService.AddLoggingForUpdatingFolderName(userName, folder.getFileName(), previousFileName);
			return "Success";
		}
		
		return "This folder is not belonged to your account !";
	}
	
	public String DeleteFolder(int folderID, String userName) throws IOException, ExecutionException, InterruptedException {
		File existingFolder = firestore.collection("File").document(String.valueOf(folderID)).get().get().toObject(File.class);
		
		if (existingFolder.getCreatedUser().equals(userName))
		{						
			firestore.collection("File").document(String.valueOf(folderID)).delete();
			firestore.collection("DeletedFile").document(String.valueOf(folderID)).set(existingFolder);
			documentService.UpdateSize(existingFolder.getSize(), existingFolder.getLocation(), "-");
			ChangeStatusOfAllSharingAndSavingFiles(folderID, Status.Inactive);
			ChangeStatus(folderID, Status.Inactive);
			activityLoggingService.AddLoggingForDeletingFolder(userName, existingFolder.getFileName());
			return "Success";
		}
		
		return "This folder is not belonged to your account !";
	}
	
	private void ChangeStatusOfAllSharingAndSavingFiles(int docID, Status status) throws InterruptedException, ExecutionException, IOException {		
		for (var p : firestore.collection("File").get().get().getDocuments())
		{
			var file = p.toObject(File.class);
			if (file.getLocation() == docID)
			{
				if (file.getType() == FileType.Folder)
				{
					ChangeStatusOfAllSharingAndSavingFiles(file.getFileID(), status);
				}
				ChangeStatus(file.getFileID(), status);
			}
		}
	}
	
	private void ChangeStatus(int docID, Status status) throws ExecutionException, IOException, InterruptedException {
		for (var p : firestore.collection("SavingDocuments").get().get().getDocuments())
		{
			var file = p.toObject(SavingDocument.class);
			if (file.getFileID() == docID)
			{
				file.setStatus(status);
				firestore.collection("SavingDocuments").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).set(file);
			}
		}
		
		for (var p : firestore.collection("SharingDocument").get().get().getDocuments())
		{
			var file = p.toObject(SharingDocument.class);
			if (file.getFileID() == docID)
			{
				file.setStatus(status);
				firestore.collection("SharingDocument").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).set(file);
			}
		}
	}
	
	public String DeleteFolderPermanently(int folderID, String userName) throws IOException, ExecutionException, InterruptedException {
		File existingFolder = firestore.collection("DeletedFile").document(String.valueOf(folderID)).get().get().toObject(File.class);
		
		if (existingFolder.getCreatedUser().equals(userName))
		{			
			DeleteAllFileInFolder(folderID);
			DeleteAllDeletedFileInFolder(folderID);
			DeleteAllSharingAndSavingFiles(folderID);
			
			firestore.collection("DeletedFile").document(String.valueOf(folderID)).delete();
			activityLoggingService.AddLoggingForDeletingFolderPermanently(userName, existingFolder.getFileName());
			return "Success";
		}
		
		return "This folder is not belonged to your account !";
	}
	
	private void DeleteAllSharingAndSavingFiles(int docID) throws IOException, InterruptedException, ExecutionException {	
		for (var p : firestore.collection("SavingDocuments").get().get().getDocuments())
		{
			var file = p.toObject(SavingDocument.class);
			if (file.getFileID() == docID)
			{
				firestore.collection("SavingDocuments").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).delete();
			}
		}
		
		for (var p : firestore.collection("SharingDocument").get().get().getDocuments())
		{
			var file = p.toObject(SharingDocument.class);
			if (file.getFileID() == docID)
			{
				firestore.collection("SharingDocument").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).delete();
			}
		}
	}
	
	private void DeleteAllFileInFolder(int folderID) throws IOException, ExecutionException, InterruptedException {
		for (var document : firestore.collection("File").get().get().getDocuments())
		{
			File folder = document.toObject(File.class);
			
			if (folder.getLocation() == folderID)
			{
				DeleteAllSharingAndSavingFiles(folder.getFileID());
				if (folder.getType() == FileType.Folder)
				{
					DeleteAllFileInFolder(folder.getFileID());
					firestore.collection("File").document(String.valueOf(folder.getFileID())).delete();		
				}
				else
				{
					firestore.collection("File").document(String.valueOf(folder.getFileID())).delete();		
					Bucket bucket = StorageClient.getInstance().bucket(); 
					Blob blob = bucket.get(folder.getNameOnCloud());
					blob.delete();
				}
			}
			
		}
	}
	
	private void DeleteAllDeletedFileInFolder(int folderID) throws IOException, ExecutionException, InterruptedException {
		for (var document : firestore.collection("DeletedFile").get().get().getDocuments())
		{
			File folder = document.toObject(File.class);
			
			if (folder.getLocation() == folderID)
			{
				DeleteAllSharingAndSavingFiles(folder.getFileID());
				if (folder.getType() == FileType.Folder)
				{
					DeleteAllDeletedFileInFolder(folder.getFileID());
					firestore.collection("DeletedFile").document(String.valueOf(folder.getFileID())).delete();		
				}
				else
				{
					firestore.collection("DeletedFile").document(String.valueOf(folder.getFileID())).delete();		
					Bucket bucket = StorageClient.getInstance().bucket(); 
					Blob blob = bucket.get(folder.getNameOnCloud());
					blob.delete();
				}
			}
		}
	}

	private void DeleteSharingAndSavingFile(int fileID) throws IOException, InterruptedException, ExecutionException {
		for (var p : firestore.collection("SavingDocuments").get().get().getDocuments())
		{
			var file = p.toObject(SavingDocument.class);
			if (file.getFileID() == fileID)
			{
				firestore.collection("SavingDocuments").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).delete();
			}
		}
		
		for (var p : firestore.collection("SharingDocument").get().get().getDocuments())
		{
			var file = p.toObject(SharingDocument.class);
			if (file.getFileID() == fileID)
			{
				firestore.collection("SharingDocument").document(file.getUserName() + "-" + String.valueOf(file.getFileID())).delete();
			}
		}		
	}

	public List<FileDTO> GetAllFolderOfUser(String userName) throws ExecutionException, InterruptedException {
		List<FileDTO> files = new ArrayList<>();
		ApiFuture<QuerySnapshot> query = firestore.collection("File").get();
		
		for (DocumentSnapshot document : query.get().getDocuments()) {
			File folder = document.toObject(File.class);
	        if (userName.equals(folder.getCreatedUser()) && folder.getLocation() == 0)
	        {
	        	files.add(new FileDTO(folder, firestore));	        	
	        }
	    }
		
		return files.stream().sorted((t1,t2) -> t2.getCreatedTime().compareTo(t1.getCreatedTime())).collect(Collectors.toList());
	}
	
	public String RestoreFile(int fileID, String userName) throws ExecutionException, InterruptedException, IOException {
		var file = firestore.collection("DeletedFile").document(String.valueOf(fileID)).get().get().toObject(File.class);
		
		if (file.getCreatedUser().equals(userName))
		{
			firestore.collection("File").document(String.valueOf(file.getFileID())).set(file);
			firestore.collection("DeletedFile").document(String.valueOf(file.getFileID())).delete();
			if (file.getType() == FileType.Folder)
			{
				ChangeStatusOfAllSharingAndSavingFiles(fileID, Status.Active);
			}
			ChangeStatus(fileID, Status.Active);
			return "Success";
		}
		
		return "This folder is not belonged to your account !";
	}
	
	public List<FileDTO> getAllDeletedFile(String userName) throws IOException, ExecutionException, InterruptedException {
		List<FileDTO> files = new ArrayList<>();
		
		for (var p : firestore.collection("DeletedFile").get().get().getDocuments())
		{
			var file = p.toObject(File.class);
			if (file.getCreatedUser().equals(userName))
			{
				files.add(new FileDTO(file, firestore));
			}
		}
		
		return files.stream().sorted((p1,p2) -> p2.getCreatedTime().compareTo(p1.getCreatedTime())).toList();
	}
}
