// src/features/auth/authStyles.ts
import { StyleSheet } from 'react-native';

export const authStyles = StyleSheet.create({
  container: { 
    flex: 1, 
    padding: 24, 
    backgroundColor: 'white',
    justifyContent: 'center', 
  },
  fixedLogo: { 
    width: 120, 
    height: 120, 
    alignSelf: 'center', 
    position: 'absolute', 
    top: 60, 
  },
  headerTitle: { 
    fontSize: 24, 
    fontWeight: '700', 
    marginBottom: 20, 
    textAlign: 'center', 
    color: 'black',
    marginTop: 80, 
  },
  inputContainer: { 
    marginBottom: 20, 
  },
  label: { 
    fontSize: 16, 
    fontWeight: '600', 
    marginBottom: 8, 
    color: '#333', 
  },
  input: { 
    height: 50, 
    borderWidth: 1, 
    borderColor: '#E5E5E5', 
    borderRadius: 8, 
    paddingHorizontal: 16, 
    fontSize: 16, 
    backgroundColor: '#FAFAFA', 
    color: 'black', 
  },
  button: { 
    paddingVertical: 16, 
    marginTop: 20, 
    borderRadius: 10, 
    backgroundColor: 'black', 
  },
  buttonText: { 
    color: 'white', 
    fontSize: 16, 
    fontWeight: '600', 
    textAlign: 'center', 
  },
  backButton: { 
    marginTop: 16, 
    alignSelf: 'center', 
    padding: 8 
  },
  backButtonText: { 
    color: '#666', 
    fontSize: 14, 
    fontWeight: '500' 
  },
  buttonSelected: {
    backgroundColor: 'white',
    borderWidth: 1,
    borderColor: 'black',
  },
  buttonTextSelected: {
    color: 'black',
    fontWeight: '600',
  },
});